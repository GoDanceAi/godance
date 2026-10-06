package ai.godance.web.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import ai.godance.domain.GalleryItem;

public final class GalleryDtos {

    private GalleryDtos() {}

    public record GalleryItemView(
            UUID id,
            String title,
            String prompt,
            String negativePrompt,
            String authorName,
            String modality,
            String model,
            String provider,
            String aspectRatio,
            String duration,
            Long seed,
            String outputUrl,
            String thumbnailUrl,
            int likesCount,
            int remixCount,
            boolean isOfficial,
            String category,
            List<String> tags,
            String forkedFromTitle,
            Instant createdAt
    ) {
        public static GalleryItemView from(GalleryItem item) {
            return new GalleryItemView(
                    item.getId(),
                    item.getTitle(),
                    item.getPrompt(),
                    item.getNegativePrompt(),
                    item.getAuthorName(),
                    item.getModality().name(),
                    item.getModel(),
                    item.getProvider(),
                    item.getAspectRatio(),
                    item.getDuration(),
                    item.getSeed(),
                    item.getOutputUrl(),
                    item.getThumbnailUrl(),
                    item.getLikesCount(),
                    item.getRemixCount(),
                    item.isOfficial(),
                    item.getCategory(),
                    item.getTags(),
                    item.getForkedFromTitle(),
                    item.getCreatedAt()
            );
        }
    }

    public record PublishGalleryRequest(
            UUID jobId,
            String title,
            String prompt,
            String negativePrompt,
            String modality,
            String model,
            String provider,
            String aspectRatio,
            String duration,
            Long seed,
            String outputUrl,
            String thumbnailUrl,
            String category,
            List<String> tags,
            String forkedFromTitle
    ) {}

    public record GalleryListResponse(
            List<GalleryItemView> items,
            int page,
            int totalPages,
            long totalElements
    ) {}

    public record LikeResponse(
            UUID id,
            int likesCount
    ) {}

    public record ShareRewardResponse(
            boolean success,
            long rewardCents,
            long claimsToday,
            long maxDailyClaims,
            long newBalanceCents
    ) {}
}
