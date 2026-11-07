package ai.mnemosyne.cisys;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Column;

@Entity
public class Project extends PanacheEntity {
    @Column(nullable = false, length = 100) public String name;
    @Column(nullable = false, length = 2048) public String repositoryUrl;
    @Column(nullable = false) public boolean privateRepository;
    @Column(nullable = false, length = 255) public String ownerSubject;
}
