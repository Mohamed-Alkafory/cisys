package ai.mnemosyne.cisys;
import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.*;
import java.time.Instant;
@Entity public class Build extends PanacheEntity {
 public Long projectId;
 public String requestedBy;
 public String status;
 public String revision;
 public Instant startedAt;
 public Instant finishedAt;
 @Column(length=131072) public String output;
}
