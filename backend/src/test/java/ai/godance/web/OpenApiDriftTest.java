package ai.godance.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.yaml.snakeyaml.Yaml;

import com.fasterxml.jackson.annotation.JsonProperty;

import ai.godance.service.ParentalNotice;
import ai.godance.web.dto.ApiKeyDtos;
import ai.godance.web.dto.AttributionDtos;
import ai.godance.web.dto.AuthDtos;
import ai.godance.web.dto.BillingDtos;
import ai.godance.web.dto.ChatCompletionDtos;
import ai.godance.web.dto.ConversationDtos;
import ai.godance.web.dto.EstimateDtos;
import ai.godance.web.dto.ImageDtos;
import ai.godance.web.dto.ImageEditDtos;
import ai.godance.web.dto.InputDtos;
import ai.godance.web.dto.OpenAiVideoDtos;
import ai.godance.web.dto.ParentalConsentDtos;
import ai.godance.web.dto.RecipeDtos;
import ai.godance.web.dto.ReferralDtos;
import ai.godance.web.dto.SavedRecipeDtos;
import ai.godance.web.dto.TwoFactorDtos;
import ai.godance.web.dto.VideoDtos;

/**
 * Keeps {@code static/openapi.yaml}, which is written by hand, from
 * drifting away from the code. It reads the code by reflection and needs
 * no running application.
 *
 * <ul>
 *   <li><b>Routes, both ways.</b> Every {@code @RequestMapping} and verb
 *       mapping of every controller (admin routes excluded) must be a path
 *       and method in the document, and the document may name no path or
 *       method the code lacks.</li>
 *   <li><b>Schemas.</b> A schema listed in {@link #SCHEMA_RECORDS} must
 *       have exactly the JSON fields of its record.</li>
 *   <li><b>Shape.</b> Every operation has an id, a summary, responses and
 *       states who may call it; every {@code $ref} resolves.</li>
 * </ul>
 *
 * <p><b>Adding a route:</b> add its path and method under {@code paths:} in
 * the document. <b>Adding a field to a record that is listed here:</b> add
 * it to the schema. <b>Adding a schema for a new record:</b> add the pair
 * to {@link #SCHEMA_RECORDS}.
 */
class OpenApiDriftTest {

    private static final String DOCUMENT = "/static/openapi.yaml";

    /** Routes that are deliberately not described: the operator console. */
    private static final String ADMIN_PREFIX = "/v1/admin";

    private static final Set<String> METHODS = Set.of("get", "put", "post", "delete", "patch", "head", "options");

    /**
     * Each schema that describes a record, and the record. The schema's
     * properties must be the record's JSON fields, no more and no fewer.
     */
    private static final Map<String, Class<? extends Record>> SCHEMA_RECORDS = schemaRecords();

    private static Map<String, Class<? extends Record>> schemaRecords() {
        Map<String, Class<? extends Record>> m = new LinkedHashMap<>();
        m.put("Status", StatusController.StatusResponse.class);
        m.put("ProviderStatus", StatusController.ProviderStatus.class);
        m.put("ModelList", ModelsController.ModelListResponse.class);
        m.put("Model", ModelsController.ModelEntry.class);
        m.put("ChatCompletionRequest", ChatCompletionDtos.ChatCompletionRequest.class);
        m.put("ChatMessage", ChatCompletionDtos.Message.class);
        m.put("ChatCompletion", ChatCompletionDtos.ChatCompletionResponse.class);
        m.put("ChatChoice", ChatCompletionDtos.Choice.class);
        m.put("ChatResponseMessage", ChatCompletionDtos.ResponseMessage.class);
        m.put("TokenUsage", ChatCompletionDtos.UsageBlock.class);
        m.put("ChatCompletionChunk", ChatCompletionDtos.ChatCompletionChunk.class);
        m.put("ChatChunkChoice", ChatCompletionDtos.ChunkChoice.class);
        m.put("ChatChunkDelta", ChatCompletionDtos.ChunkDeltaBlock.class);
        m.put("ImageGenerationRequest", ImageDtos.ImageGenerationRequest.class);
        m.put("ImageResponse", ImageDtos.ImageGenerationResponse.class);
        m.put("ImageData", ImageDtos.ImageData.class);
        m.put("JobAccepted", ImageDtos.AsyncJobAcknowledged.class);
        m.put("ImageEditRequest", ImageEditDtos.ImageEditRequest.class);
        m.put("InputRef", InputDtos.InputRef.class);
        m.put("JobOutputRef", InputDtos.JobOutputRef.class);
        m.put("VideoGenerationRequest", VideoDtos.VideoGenerationRequest.class);
        m.put("VideoCreateRequest", OpenAiVideoDtos.CreateRequest.class);
        m.put("VideoInputReference", OpenAiVideoDtos.InputReference.class);
        m.put("Video", OpenAiVideoDtos.Video.class);
        m.put("VideoGenerationError", OpenAiVideoDtos.VideoError.class);
        m.put("VideoList", OpenAiVideoDtos.VideoPage.class);
        m.put("VideoDeleted", OpenAiVideoDtos.Deleted.class);
        m.put("Job", ImageDtos.JobView.class);
        m.put("JobOutput", ImageDtos.JobOutputView.class);
        m.put("JobInput", InputDtos.JobInputView.class);
        m.put("JobRecipe", ImageDtos.RecipeRunView.class);
        m.put("SavedJobs", ImageDtos.SavedJobsView.class);
        m.put("File", InputDtos.InputFileView.class);
        m.put("AcceptInputTermsRequest", InputDtos.AcceptTermsRequest.class);
        m.put("RecipeRef", RecipeDtos.RecipeRef.class);
        m.put("RecipeVariable", RecipeDtos.RecipeVariable.class);
        m.put("RecipeInputSlot", RecipeDtos.RecipeInputSlot.class);
        m.put("CreateRecipeRequest", RecipeDtos.CreateRecipeRequest.class);
        m.put("NewRecipeVersionRequest", RecipeDtos.NewVersionRequest.class);
        m.put("RenameRecipeRequest", RecipeDtos.RenameRecipeRequest.class);
        m.put("RecipeVersion", RecipeDtos.RecipeVersionView.class);
        m.put("Recipe", RecipeDtos.RecipeView.class);
        m.put("EstimateRequest", EstimateDtos.EstimateRequest.class);
        m.put("Estimate", EstimateDtos.EstimateResponse.class);
        m.put("UsageRecord", UsageController.UsageView.class);
        m.put("ApiKeyCreateRequest", ApiKeyDtos.CreateApiKeyRequest.class);
        m.put("ApiKeyUpdateRequest", ApiKeyDtos.UpdateApiKeyRequest.class);
        m.put("ApiKeyCreated", ApiKeyDtos.CreateApiKeyResponse.class);
        m.put("ApiKey", ApiKeyDtos.ApiKeySummary.class);
        m.put("SignupRequest", AuthDtos.SignupRequest.class);
        m.put("LoginRequest", AuthDtos.LoginRequest.class);
        m.put("ForgotPasswordRequest", AuthDtos.ForgotPasswordRequest.class);
        m.put("ResetPasswordRequest", AuthDtos.ResetPasswordRequest.class);
        m.put("ReleaseEmailRequest", AuthDtos.ReleaseEmailRequest.class);
        m.put("ReleaseLinkRequest", AuthDtos.ReleaseLinkRequest.class);
        m.put("ChangeEmailRequest", AuthDtos.ChangeEmailRequest.class);
        m.put("AuthSession", AuthDtos.AuthResponse.class);
        m.put("Me", AuthDtos.MeResponse.class);
        m.put("InputsStatus", InputDtos.InputsStatus.class);
        m.put("TwoFactorStatus", TwoFactorDtos.StatusResponse.class);
        m.put("TwoFactorEnrolRequest", TwoFactorDtos.EnrolRequest.class);
        m.put("TwoFactorEnrolment", TwoFactorDtos.EnrolResponse.class);
        m.put("TwoFactorCodeRequest", TwoFactorDtos.EnableRequest.class);
        m.put("TwoFactorEnabled", TwoFactorDtos.EnableResponse.class);
        m.put("TwoFactorDisableRequest", TwoFactorDtos.DisableRequest.class);
        m.put("ParentalConsent", ParentalConsentDtos.ParentalConsentView.class);
        m.put("ParentalNotice", ParentalNotice.class);
        m.put("ParentalConsentCheckoutRequest", ParentalConsentDtos.ParentalConsentCheckoutRequest.class);
        m.put("TopUpRequest", BillingDtos.TopUpRequest.class);
        m.put("TopUp", BillingDtos.TopUpResponse.class);
        m.put("AutoRechargeRequest", BillingDtos.AutoRechargeConfigRequest.class);
        m.put("AutoRecharge", BillingDtos.AutoRechargeConfigView.class);
        m.put("CreateConversationRequest", ConversationDtos.CreateConversationRequest.class);
        m.put("Conversation", ConversationDtos.ConversationView.class);
        m.put("AppendMessageRequest", ConversationDtos.AppendMessageRequest.class);
        m.put("ConversationMessage", ConversationDtos.MessageView.class);
        m.put("Referrals", ReferralDtos.EnabledView.class);
        m.put("ReferralsDisabled", ReferralDtos.DisabledView.class);
        m.put("ReferralClaimRequest", ReferralDtos.ClaimRequest.class);
        m.put("ReferralClaim", ReferralDtos.ClaimResponse.class);
        m.put("SavedGalleryRecipe", SavedRecipeDtos.SavedRecipeView.class);
        m.put("FirstTouchRequest", AttributionDtos.FirstTouchRequest.class);
        return m;
    }

    static String text;
    static Map<String, Object> document;

    @BeforeAll
    static void load() throws IOException {
        try (InputStream in = OpenApiDriftTest.class.getResourceAsStream(DOCUMENT)) {
            assertThat(in).as(DOCUMENT + " is on the classpath").isNotNull();
            text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        document = new Yaml().load(text);
    }

    // ===== Routes =====================================================

    @Test
    void paths_givenEveryRouteInTheCode_expectsEachOneDescribed() {
        Set<String> missing = new TreeSet<>(routesInCode());
        missing.removeAll(routesInDocument());

        assertThat(missing)
                .as("routes the code serves that static/openapi.yaml does not describe; "
                        + "add each under paths: with its method")
                .isEmpty();
    }

    @Test
    void paths_givenEveryPathInTheDocument_expectsARouteInTheCodeForEach() {
        Set<String> stale = new TreeSet<>(routesInDocument());
        stale.removeAll(routesInCode());

        assertThat(stale)
                .as("paths static/openapi.yaml describes that no controller serves; "
                        + "nothing is documented that is not live")
                .isEmpty();
    }

    @Test
    void paths_givenTheAdminConsole_expectsNoneOfItInTheDocument() {
        assertThat(routesInDocument()).noneMatch(route -> route.contains(" " + ADMIN_PREFIX));
        // The exclusion is real: there are admin routes in the code to leave out.
        assertThat(everyRouteInCode()).anyMatch(route -> route.contains(" " + ADMIN_PREFIX));
    }

    @Test
    void paths_givenTheLineLayoutOtherChecksReadItBy_expectsTheSameRoutesAsTheParser() {
        // marketing's llms-full.txt check reads this file without a YAML parser: a path is a
        // key indented two spaces under "paths:", a method a key indented four. If the file
        // is ever laid out differently, this fails here rather than that check going blind.
        assertThat(routesByLine()).isEqualTo(routesInDocument());
    }

    // ===== Operations =================================================

    @Test
    void operations_givenEachOne_expectsIdSummaryTagsResponsesAndWhoMayCall() {
        Set<String> declaredTags = new TreeSet<>();
        for (Object tag : list(document.get("tags"))) declaredTags.add((String) map(tag).get("name"));
        Set<String> schemes = map(map(document.get("components")).get("securitySchemes")).keySet();
        assertThat(schemes).containsExactlyInAnyOrder("apiKey", "session");

        Map<String, String> ids = new HashMap<>();
        forEachOperation((route, operation) -> {
            assertThat(operation.get("operationId")).as(route + " operationId").isInstanceOf(String.class);
            String earlier = ids.put((String) operation.get("operationId"), route);
            assertThat(earlier).as("operationId " + operation.get("operationId") + " is used twice").isNull();
            assertThat((String) operation.get("summary")).as(route + " summary").isNotBlank();
            assertThat(list(operation.get("tags"))).as(route + " tags").isNotEmpty()
                    .allSatisfy(tag -> assertThat(declaredTags).as(route + " tag").contains((String) tag));
            assertThat(map(operation.get("responses"))).as(route + " responses").isNotEmpty();
            // Who may call it is stated for every operation: [] is public.
            assertThat(operation).as(route + " security").containsKey("security");
            for (Object requirement : list(operation.get("security"))) {
                assertThat(map(requirement).keySet()).as(route + " security scheme")
                        .hasSize(1).allSatisfy(scheme -> assertThat(schemes).contains(scheme));
            }
        });
    }

    @Test
    void references_givenEveryRefInTheDocument_expectsItToResolve() {
        List<String> refs = new ArrayList<>();
        collectRefs(document, refs);

        assertThat(refs).isNotEmpty();
        for (String ref : refs) {
            assertThat(ref).startsWith("#/");
            Object node = document;
            for (String part : ref.substring(2).split("/")) {
                assertThat(node).as(ref).isInstanceOf(Map.class);
                node = map(node).get(part);
            }
            assertThat(node).as("unresolved $ref " + ref).isNotNull();
        }
    }

    @Test
    void document_givenEveryMapping_expectsNoStrayKeysFromAnUnquotedSentence() {
        // In a one-line mapping ({ type: string, description: … }) an unquoted sentence with a
        // comma in it is cut at the comma, and its tail becomes a key with no value. The file
        // still parses, and says something else. No real key has a space or lacks a value.
        List<String> stray = new ArrayList<>();
        collectStrayKeys(document, "", stray);

        assertThat(stray).as("keys with a space in them, or with no value: quote the sentence they came from").isEmpty();
    }

    // ===== Schemas ====================================================

    @Test
    void schemas_givenEachRecordTheyDescribe_expectExactlyItsJsonFields() {
        Map<String, Object> schemas = map(map(document.get("components")).get("schemas"));

        SCHEMA_RECORDS.forEach((name, record) -> {
            assertThat(schemas).as("schema " + name).containsKey(name);
            Set<String> described = new TreeSet<>(map(map(schemas.get(name)).get("properties")).keySet());
            assertThat(described)
                    .as("schema " + name + " against " + record.getName()
                            + ": add or remove properties so they are the record's JSON fields")
                    .isEqualTo(jsonFields(record));
        });
    }

    // ===== Reading the code ===========================================

    /** {@code "GET /v1/jobs/{id}"} for every mapping of every controller, admin routes left out. */
    private static Set<String> routesInCode() {
        Set<String> routes = new TreeSet<>();
        for (String route : everyRouteInCode()) {
            if (!route.contains(" " + ADMIN_PREFIX)) routes.add(route);
        }
        return routes;
    }

    private static Set<String> everyRouteInCode() {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Controller.class));   // @RestController is one
        Set<String> routes = new TreeSet<>();
        // The whole application, so a controller added outside web/ is seen as well.
        for (BeanDefinition candidate : scanner.findCandidateComponents("ai.godance")) {
            Class<?> controller;
            try {
                controller = Class.forName(candidate.getBeanClassName());
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException(e);
            }
            RequestMapping onClass = AnnotatedElementUtils.findMergedAnnotation(controller, RequestMapping.class);
            String[] prefixes = onClass == null || onClass.path().length == 0 ? new String[] {""} : onClass.path();
            for (Method method : controller.getDeclaredMethods()) {
                RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
                if (mapping == null) continue;
                assertThat(mapping.method())
                        .as(controller.getSimpleName() + "." + method.getName() + " names its HTTP method")
                        .isNotEmpty();
                String[] paths = mapping.path().length == 0 ? new String[] {""} : mapping.path();
                for (String prefix : prefixes) {
                    for (String path : paths) {
                        for (RequestMethod verb : mapping.method()) {
                            routes.add(verb.name() + " " + prefix + path);
                        }
                    }
                }
            }
        }
        assertThat(routes).as("controllers were found").hasSizeGreaterThan(50);
        return routes;
    }

    /** The JSON field of each component of a record: its {@code @JsonProperty} name, else its own. */
    private static Set<String> jsonFields(Class<? extends Record> record) {
        Set<String> fields = new TreeSet<>();
        for (RecordComponent component : record.getRecordComponents()) {
            JsonProperty named = component.getAccessor().getAnnotation(JsonProperty.class);
            fields.add(named != null && !named.value().isEmpty() ? named.value() : component.getName());
        }
        return fields;
    }

    // ===== Reading the document =======================================

    private static Set<String> routesInDocument() {
        Set<String> routes = new TreeSet<>();
        forEachOperation((route, operation) -> routes.add(route));
        return routes;
    }

    private interface OperationVisitor {
        void visit(String route, Map<String, Object> operation);
    }

    private static void forEachOperation(OperationVisitor visitor) {
        map(document.get("paths")).forEach((path, item) ->
                map(item).forEach((method, operation) -> {
                    assertThat(METHODS).as(path + " has a key that is not an HTTP method: " + method).contains(method);
                    visitor.visit(method.toUpperCase() + " " + path, map(operation));
                }));
    }

    /** The routes as a reader without a YAML parser finds them; see the layout note at the top of the file. */
    private static Set<String> routesByLine() {
        Pattern pathLine = Pattern.compile("^  (/[^\\s:]*):\\s*$");
        Pattern methodLine = Pattern.compile("^    (get|put|post|delete|patch|head|options):\\s*$");
        Set<String> routes = new TreeSet<>();
        boolean inPaths = false;
        String path = null;
        for (String line : text.split("\n")) {
            if (line.equals("paths:")) {
                inPaths = true;
                continue;
            }
            if (!inPaths) continue;
            if (!line.isEmpty() && !Character.isWhitespace(line.charAt(0)) && !line.startsWith("#")) break;
            Matcher asPath = pathLine.matcher(line);
            if (asPath.matches()) {
                path = asPath.group(1);
                continue;
            }
            Matcher asMethod = methodLine.matcher(line);
            if (asMethod.matches() && path != null) routes.add(asMethod.group(1).toUpperCase() + " " + path);
        }
        return routes;
    }

    private static void collectRefs(Object node, List<String> into) {
        if (node instanceof Map<?, ?> object) {
            object.forEach((key, value) -> {
                if ("$ref".equals(key) && value instanceof String ref) into.add(ref);
                else collectRefs(value, into);
            });
        } else if (node instanceof List<?> items) {
            items.forEach(item -> collectRefs(item, into));
        }
    }

    private static void collectStrayKeys(Object node, String at, List<String> into) {
        if (node instanceof Map<?, ?> object) {
            object.forEach((key, value) -> {
                String here = at + "/" + key;
                // An example is data, not description: its keys are whatever the API's are.
                if ("example".equals(key) || "examples".equals(key)) return;
                if (!(key instanceof String name) || name.chars().anyMatch(Character::isWhitespace) || value == null) {
                    into.add(here);
                }
                collectStrayKeys(value, here, into);
            });
        } else if (node instanceof List<?> items) {
            for (int i = 0; i < items.size(); i++) collectStrayKeys(items.get(i), at + "[" + i + "]", into);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object node) {
        assertThat(node).isInstanceOf(Map.class);
        return (Map<String, Object>) node;
    }

    private static List<?> list(Object node) {
        assertThat(node).isInstanceOf(List.class);
        return (List<?>) node;
    }
}
