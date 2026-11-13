package ai.godance.web;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import ai.godance.service.GenerationQuotes;
import ai.godance.service.GenerationQuotes.Quote;
import ai.godance.web.dto.EstimateDtos.EstimateRequest;
import ai.godance.web.dto.EstimateDtos.EstimateResponse;
import jakarta.validation.Valid;
import reactor.core.publisher.Mono;

/**
 * {@code POST /v1/estimates}: the price of an image or video request before
 * it is submitted. Nothing is charged and no job is created. The figure is
 * the exact pre-debit the matching generation request takes, from the same
 * {@link GenerationQuotes}: the 1-cent minimum and a video's default clip
 * length included. An {@code n} the model cannot return answers 400, as the
 * generation request would. Unknown model 404; text model 400.
 */
@RestController
@RequestMapping("/v1")
public class EstimateController {

    private static final String CURRENCY = "USD";

    private final GenerationQuotes quotes;

    public EstimateController(GenerationQuotes quotes) {
        this.quotes = quotes;
    }

    @PostMapping("/estimates")
    public Mono<EstimateResponse> estimate(@Valid @RequestBody EstimateRequest req) {
        return Mono.fromCallable(() -> {
            Quote q = quotes.any(req.model(), req.n(), req.seconds());
            return new EstimateResponse(q.model().modelName(), q.model().modality().name(),
                    q.n(), q.seconds(), q.costCents(), CURRENCY);
        });
    }
}
