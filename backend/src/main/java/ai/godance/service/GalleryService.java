package ai.godance.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import ai.godance.domain.GalleryItem;
import ai.godance.domain.Modality;
import ai.godance.repository.GalleryItemRepository;
import ai.godance.web.dto.GalleryDtos.GalleryItemView;
import ai.godance.web.dto.GalleryDtos.GalleryListResponse;
import ai.godance.web.dto.GalleryDtos.LikeResponse;
import ai.godance.web.dto.GalleryDtos.PublishGalleryRequest;
import jakarta.persistence.criteria.Predicate;

@Service
public class GalleryService {

    private final GalleryItemRepository repository;

    public GalleryService(GalleryItemRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public GalleryListResponse listGallery(String source, String modality, String category,
                                          String model, String query, int page, int size) {
        int safePage = Math.max(0, page);
        int safeSize = Math.min(100, Math.max(1, size));

        Specification<GalleryItem> spec = (root, q, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            if (source != null && !source.isBlank()) {
                if ("OFFICIAL".equalsIgnoreCase(source)) {
                    predicates.add(cb.isTrue(root.get("official")));
                } else if ("COMMUNITY".equalsIgnoreCase(source)) {
                    predicates.add(cb.isFalse(root.get("official")));
                }
            }

            if (modality != null && !modality.isBlank() && !"ALL".equalsIgnoreCase(modality)) {
                try {
                    Modality mod = Modality.valueOf(modality.toUpperCase());
                    predicates.add(cb.equal(root.get("modality"), mod));
                } catch (IllegalArgumentException ignored) {
                    // Invalid modality string, ignore filter
                }
            }

            if (category != null && !category.isBlank() && !"ALL".equalsIgnoreCase(category)) {
                predicates.add(cb.equal(cb.upper(root.get("category")), category.toUpperCase()));
            }

            if (model != null && !model.isBlank() && !"ALL".equalsIgnoreCase(model)) {
                predicates.add(cb.equal(root.get("model"), model));
            }

            if (query != null && !query.isBlank()) {
                String searchPattern = "%" + query.toLowerCase().trim() + "%";
                Predicate titleMatch = cb.like(cb.lower(root.get("title")), searchPattern);
                Predicate promptMatch = cb.like(cb.lower(root.get("prompt")), searchPattern);
                Predicate authorMatch = cb.like(cb.lower(root.get("authorName")), searchPattern);
                predicates.add(cb.or(titleMatch, promptMatch, authorMatch));
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        };

        Page<GalleryItem> resultPage = repository.findAll(
                spec,
                PageRequest.of(safePage, safeSize, Sort.by(Sort.Direction.DESC, "createdAt"))
        );

        List<GalleryItemView> views = resultPage.getContent().stream()
                .map(GalleryItemView::from)
                .toList();

        return new GalleryListResponse(views, resultPage.getNumber(), resultPage.getTotalPages(), resultPage.getTotalElements());
    }

    @Transactional
    public GalleryItemView publish(UUID userId, String authorName, PublishGalleryRequest req) {
        GalleryItem item = new GalleryItem();
        item.setUserId(userId);
        item.setJobId(req.jobId());
        item.setTitle(req.title() != null && !req.title().isBlank() ? req.title().trim() : "Untitled Creation");
        item.setPrompt(req.prompt() != null ? req.prompt().trim() : "");
        item.setNegativePrompt(req.negativePrompt() != null ? req.negativePrompt().trim() : null);
        item.setAuthorName(authorName != null && !authorName.isBlank() ? authorName : "Anonymous Creator");

        Modality mod = Modality.IMAGE;
        if (req.modality() != null) {
            try {
                mod = Modality.valueOf(req.modality().toUpperCase());
            } catch (IllegalArgumentException ignored) {}
        }
        item.setModality(mod);
        item.setModel(req.model() != null ? req.model() : "flux-1.1-pro");
        item.setProvider(req.provider() != null ? req.provider() : "Provider");
        item.setAspectRatio(req.aspectRatio() != null ? req.aspectRatio() : "1:1");
        item.setDuration(req.duration());
        item.setSeed(req.seed());
        item.setOutputUrl(req.outputUrl() != null ? req.outputUrl() : "");
        item.setThumbnailUrl(req.thumbnailUrl() != null && !req.thumbnailUrl().isBlank() ? req.thumbnailUrl() : req.outputUrl());
        item.setCategory(req.category() != null ? req.category().toUpperCase() : "ART");
        item.setTags(req.tags() != null ? req.tags() : List.of());
        item.setForkedFromTitle(req.forkedFromTitle());
        item.setOfficial(false);

        GalleryItem saved = repository.save(item);
        return GalleryItemView.from(saved);
    }

    @Transactional
    public LikeResponse like(UUID id) {
        repository.incrementLikes(id);
        Optional<GalleryItem> item = repository.findById(id);
        return new LikeResponse(id, item.map(GalleryItem::getLikesCount).orElse(1));
    }

    @Transactional
    public void recordRemix(UUID id) {
        repository.incrementRemix(id);
    }
}
