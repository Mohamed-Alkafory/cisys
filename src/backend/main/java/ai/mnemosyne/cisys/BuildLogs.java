/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

package ai.mnemosyne.cisys;

import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.postgresql.PGConnection;
import org.postgresql.largeobject.LargeObject;
import org.postgresql.largeobject.LargeObjectManager;

/** Stores each build log as one PostgreSQL large object, through JDBC rather than Hibernate. */
@ApplicationScoped
public class BuildLogs {
    static final int FLUSH_BYTES = 1 << 20;
    static final int READ_BYTES = 1 << 20;

    @Inject DataSource dataSource;

    private volatile long flushIntervalMillis = 5000;
    private volatile boolean failWrites;
    private ScheduledExecutorService flusher;

    @PostConstruct
    void start() {
        flusher = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "cisys-log-flusher");
            t.setDaemon(true);
            return t;
        });
    }

    @PreDestroy
    void stop() { flusher.shutdownNow(); }

    /** For tests. */
    void flushInterval(long millis) { flushIntervalMillis = millis; }

    /** For tests. */
    void failWrites(boolean fail) { failWrites = fail; }

    public Writer open(long buildId) {
        long oid = QuarkusTransaction.requiringNew().call(() -> {
            try (Connection c = dataSource.getConnection()) {
                Long existing = logOid(c, buildId, true);
                if (existing != null) return existing;
                long created = largeObjects(c).createLO(LargeObjectManager.READWRITE);
                try (PreparedStatement s = c.prepareStatement("update build set logoid = cast(? as oid) where id = ?")) {
                    s.setLong(1, created);
                    s.setLong(2, buildId);
                    s.executeUpdate();
                }
                return created;
            }
        });
        return new Writer(oid);
    }

    /** Returns the storage failure, or null. */
    public Throwable appendLine(long buildId, String text) {
        Writer w;
        try { w = open(buildId); } catch (RuntimeException e) { return e; }
        w.line(text);
        w.close();
        return w.failure();
    }

    public void streamTo(long buildId, OutputStream out) throws IOException {
        Long oid = logOid(buildId);
        if (oid == null) return;
        for (long offset = 0; ; ) {
            byte[] part = read(oid, offset, READ_BYTES);
            if (part.length > 0) {
                out.write(part);
                out.flush();
                offset += part.length;
            }
            if (part.length < READ_BYTES) return;
        }
    }

    /** For tests. */
    String readAll(long buildId) {
        try (ByteArrayOutputStream all = new ByteArrayOutputStream()) {
            streamTo(buildId, all);
            return all.toString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    Long logOid(long buildId) {
        return QuarkusTransaction.requiringNew().call(() -> {
            try (Connection c = dataSource.getConnection()) {
                return logOid(c, buildId, false);
            }
        });
    }

    private static Long logOid(Connection c, long buildId, boolean lock) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("select logoid from build where id = ?" + (lock ? " for update" : ""))) {
            s.setLong(1, buildId);
            try (ResultSet r = s.executeQuery()) {
                if (!r.next()) throw new IllegalArgumentException("No build " + buildId);
                long oid = r.getLong(1);
                return r.wasNull() ? null : oid;
            }
        }
    }

    private static LargeObjectManager largeObjects(Connection c) throws SQLException {
        return c.unwrap(PGConnection.class).getLargeObjectAPI();
    }

    private byte[] read(long oid, long offset, int length) {
        return QuarkusTransaction.requiringNew().call(() -> {
            try (Connection c = dataSource.getConnection()) {
                LargeObject lo = largeObjects(c).open(oid, LargeObjectManager.READ);
                try {
                    lo.seek64(offset, LargeObject.SEEK_SET);
                    return lo.read(length);
                } finally {
                    lo.close();
                }
            }
        });
    }

    private void writeAtEnd(long oid, byte[] data, int length) {
        if (failWrites) throw new IllegalStateException("Log writes disabled for testing");
        QuarkusTransaction.requiringNew().call(() -> {
            try (Connection c = dataSource.getConnection()) {
                LargeObject lo = largeObjects(c).open(oid, LargeObjectManager.READWRITE);
                try {
                    lo.seek64(0, LargeObject.SEEK_END);
                    lo.write(data, 0, length);
                } finally {
                    lo.close();
                }
            }
            return null;
        });
    }

    /** Never throws: a storage failure is recorded and later output is dropped, so the build is never blocked. */
    public final class Writer implements BuildRunner.LogSink, AutoCloseable {
        private final long oid;
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private boolean closed;
        private boolean failed;
        private Throwable failure;
        private final ScheduledFuture<?> task;

        private Writer(long oid) {
            this.oid = oid;
            long interval = flushIntervalMillis;
            this.task = flusher.scheduleWithFixedDelay(this::periodicFlush, interval, interval, TimeUnit.MILLISECONDS);
        }

        @Override
        public synchronized void append(byte[] bytes, int off, int len) {
            while (len > 0 && !closed && !failed) {
                int n = Math.min(len, FLUSH_BYTES - buffer.size());
                buffer.write(bytes, off, n);
                off += n;
                len -= n;
                if (buffer.size() >= FLUSH_BYTES) flush();
            }
        }

        public synchronized void line(String text) {
            byte[] b = (text + "\n").getBytes(StandardCharsets.UTF_8);
            append(b, 0, b.length);
        }

        public synchronized Throwable failure() { return failure; }

        /** For tests. */
        synchronized int buffered() { return buffer.size(); }

        private synchronized void periodicFlush() {
            if (!closed) flush();
        }

        private void flush() {
            if (failed || buffer.size() == 0) return;
            try {
                writeAtEnd(oid, buffer.toByteArray(), buffer.size());
                buffer.reset();
            } catch (Throwable e) {
                failure = e;
                failed = true;
                buffer.reset();
            }
        }

        @Override
        public void close() {
            task.cancel(false);
            synchronized (this) {
                if (closed) return;
                flush();
                closed = true;
            }
        }
    }
}
