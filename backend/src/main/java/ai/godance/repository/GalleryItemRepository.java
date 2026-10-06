package ai.godance.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import ai.godance.domain.GalleryItem;

public interface GalleryItemRepository extends JpaRepository<GalleryItem, UUID>, JpaSpecificationExecutor<GalleryItem> {

    @Modifying
    @Transactional
    @Query("UPDATE GalleryItem g SET g.likesCount = g.likesCount + 1 WHERE g.id = :id")
    int incrementLikes(@Param("id") UUID id);

    @Modifying
    @Transactional
    @Query("UPDATE GalleryItem g SET g.remixCount = g.remixCount + 1 WHERE g.id = :id")
    int incrementRemix(@Param("id") UUID id);
}
