package ai.mnemosyne.cisys;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Column;

/** A container image that admins have allowed for builds. */
@Entity
public class AllowedImage extends PanacheEntityBase {
    @Id @Column(length = 201) public String name;

    public static final String PATTERN = "[A-Za-z0-9][A-Za-z0-9_./:@-]{0,200}";

    public static boolean isAllowed(String name) {
        return name != null && findById(name) != null;
    }
}
