package ai.godance.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

import ai.godance.domain.GalleryItem;
import ai.godance.domain.Modality;
import ai.godance.repository.GalleryItemRepository;
import ai.godance.web.dto.GalleryDtos.GalleryItemView;
import ai.godance.web.dto.GalleryDtos.GalleryListResponse;
import ai.godance.web.dto.GalleryDtos.LikeResponse;
import ai.godance.web.dto.GalleryDtos.PublishGalleryRequest;

@ExtendWith(MockitoExtension.class)
class GalleryServiceTest {

    @Mock
    private GalleryItemRepository repository;

    private GalleryService service;

    @BeforeEach
    void setUp() {
        service = new GalleryService(repository);
    }

    @Test
    void listGallery_returnsPagedItems() {
        GalleryItem item = new GalleryItem();
        item.setId(UUID.randomUUID());
        item.setTitle("Cyberpunk City");
        item.setPrompt("A rainy neon street");
        item.setAuthorName("Alice");
        item.setModality(Modality.IMAGE);
        item.setModel("flux-1.1-pro");
        item.setProvider("Black Forest Labs");
        item.setAspectRatio("16:9");
        item.setOutputUrl("https://example.com/image.png");
        item.setThumbnailUrl("https://example.com/thumb.png");
        item.setLikesCount(10);
        item.setCreatedAt(Instant.now());

        when(repository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(item)));

        GalleryListResponse res = service.listGallery("ALL", "IMAGE", "ALL", "ALL", "neon", 0, 10);

        assertThat(res.items()).hasSize(1);
        assertThat(res.items().get(0).title()).isEqualTo("Cyberpunk City");
        assertThat(res.items().get(0).authorName()).isEqualTo("Alice");
        assertThat(res.items().get(0).likesCount()).isEqualTo(10);
    }

    @Test
    void publish_savesItemAndReturnsView() {
        UUID userId = UUID.randomUUID();
        PublishGalleryRequest req = new PublishGalleryRequest(
                UUID.randomUUID(),
                "New Artwork",
                "Epic fantasy scene",
                "ugly, blurry",
                "IMAGE",
                "flux-1.1-pro",
                "Black Forest Labs",
                "1:1",
                null,
                12345L,
                "https://example.com/out.png",
                "https://example.com/thumb.png",
                "GAME",
                List.of("game", "fantasy"),
                "Original Recipe"
        );

        when(repository.save(any(GalleryItem.class))).thenAnswer(invocation -> {
            GalleryItem saved = invocation.getArgument(0);
            saved.setId(UUID.randomUUID());
            saved.setCreatedAt(Instant.now());
            return saved;
        });

        GalleryItemView view = service.publish(userId, "Creator One", req);

        assertThat(view.title()).isEqualTo("New Artwork");
        assertThat(view.authorName()).isEqualTo("Creator One");
        assertThat(view.modality()).isEqualTo("IMAGE");
        assertThat(view.forkedFromTitle()).isEqualTo("Original Recipe");
        assertThat(view.isOfficial()).isFalse();
    }

    @Test
    void like_incrementsLikesCount() {
        UUID id = UUID.randomUUID();
        GalleryItem item = new GalleryItem();
        item.setId(id);
        item.setLikesCount(11);

        when(repository.findById(id)).thenReturn(Optional.of(item));

        LikeResponse res = service.like(id);

        verify(repository).incrementLikes(id);
        assertThat(res.id()).isEqualTo(id);
        assertThat(res.likesCount()).isEqualTo(11);
    }
}
