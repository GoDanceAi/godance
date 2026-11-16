package ai.godance.service;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import ai.godance.config.BatchesProperties;
import ai.godance.domain.GenerationJobOutput;
import ai.godance.domain.RecipeBatch;
import ai.godance.domain.RecipeBatchRow;
import ai.godance.repository.RecipeBatchRepository;
import ai.godance.repository.RecipeBatchRowRepository;
import ai.godance.storage.ObjectStorageClient;

/**
 * A batch's results as files: {@code results.csv}, one line per row, and
 * {@code results.zip}, every stored output. Both are written straight to
 * the response a page of rows at a time; neither is built in memory.
 *
 * <p>The CSV's links are signed when the file is written and last as long
 * as any signed link does ({@code godance.object-storage.presign-ttl});
 * downloading it again gives fresh ones. Once a row's job has been purged
 * its outputs are gone and its values are empty.
 */
@Component
public class RecipeBatchResults {

    private static final Logger log = LoggerFactory.getLogger(RecipeBatchResults.class);

    /** Rows read at a time. */
    static final int PAGE = 200;

    /** The longest the part of a zip entry's name taken from a row's value may be. */
    private static final int NAME_PART_MAX = 40;

    private final BatchesProperties props;
    private final RecipeBatchRepository batches;
    private final RecipeBatchRowRepository rows;
    private final RecipeBatchViews views;
    private final ObjectStorageClient storage;

    public RecipeBatchResults(BatchesProperties props, RecipeBatchRepository batches, RecipeBatchRowRepository rows,
                              RecipeBatchViews views, ObjectStorageClient storage) {
        this.props = props;
        this.batches = batches;
        this.rows = rows;
        this.views = views;
        this.storage = storage;
    }

    /**
     * Writes the caller's batch as CSV (RFC 4180, UTF-8): {@code index},
     * {@code status}, a column for each of the recipe's variables,
     * {@code job_id}, {@code cost_cents}, {@code error}, and a signed link
     * to each output as {@code output_1} to {@code output_n}.
     *
     * @throws BatchRefusedException 404 {@code batch_not_found}, before anything is written
     */
    public Consumer<OutputStream> csv(UUID userId, UUID batchId) {
        RecipeBatch batch = batches.findByIdAndUserId(batchId, userId).orElseThrow(BatchRefusedException::notFound);
        // As many output columns as the rows ask for, or as the largest row got if a provider returned more.
        int outputColumns = Math.max(BatchSettings.of(batch.getParams()).outputsPerRow(),
                rows.maxOutputsOfARow(batchId));
        return out -> {
            try {
                writeCsv(batch, outputColumns, out);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        };
    }

    private void writeCsv(RecipeBatch batch, int outputColumns, OutputStream out) throws IOException {
        Writer csv = new BufferedWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8));
        List<String> names = batch.getVariableNames();

        List<String> header = new ArrayList<>();
        header.add("index");
        header.add("status");
        header.addAll(names);
        header.add("job_id");
        // A batch with fallbacks (round 14) also says which model each row's job ran on, and the
        // failed job a fallback replaced. Every other batch's file is as it always was.
        boolean fallbacks = batch.isFallbacks();
        if (fallbacks) {
            header.add("model");
            header.add("retried_job_id");
        }
        header.add("cost_cents");
        header.add("error");
        for (int n = 1; n <= outputColumns; n++) header.add("output_" + n);
        writeLine(csv, header);

        int after = -1;
        while (true) {
            List<RecipeBatchRow> page = rows.findPage(batch.getId(), after, PAGE);
            if (page.isEmpty()) break;
            Map<UUID, List<GenerationJobOutput>> outputs = views.outputsOf(page);
            for (RecipeBatchRow row : page) {
                List<String> line = new ArrayList<>(header.size());
                line.add(Integer.toString(row.getRowIndex()));
                line.add(row.getStatus().name());
                Map<String, String> values = row.getVariables();
                for (String name : names) line.add(values == null ? null : values.get(name));
                line.add(row.getJobId() == null ? null : row.getJobId().toString());
                if (fallbacks) {
                    line.add(row.getJobId() == null ? null : row.getModel() != null ? row.getModel() : batch.getModel());
                    line.add(row.getRetriedJobId() == null ? null : row.getRetriedJobId().toString());
                }
                line.add(row.getCostCents() == null ? null : row.getCostCents().toString());
                line.add(row.getError());
                List<GenerationJobOutput> stored = outputs.getOrDefault(row.getJobId(), List.of());
                for (int n = 0; n < outputColumns; n++) {
                    line.add(n < stored.size() ? storage.presignedUrl(stored.get(n).getStorageKey()) : null);
                }
                writeLine(csv, line);
            }
            csv.flush();
            after = page.get(page.size() - 1).getRowIndex();
            if (page.size() < PAGE) break;
        }
        csv.flush();
    }

    private static void writeLine(Writer csv, List<String> fields) throws IOException {
        for (int i = 0; i < fields.size(); i++) {
            if (i > 0) csv.write(',');
            csv.write(escape(fields.get(i)));
        }
        csv.write("\r\n");
    }

    /** RFC 4180: a field with a comma, a quote or a line break is quoted, and its quotes doubled. */
    static String escape(String field) {
        if (field == null || field.isEmpty()) return "";
        boolean quote = false;
        for (int i = 0; i < field.length(); i++) {
            char c = field.charAt(i);
            if (c == ',' || c == '"' || c == '\n' || c == '\r') {
                quote = true;
                break;
            }
        }
        return quote ? '"' + field.replace("\"", "\"\"") + '"' : field;
    }

    /**
     * Writes every stored output of the caller's batch as a zip, each named
     * {@code <index>-<first variable's value>-<n>.<ext>}: the row's index,
     * the value of the recipe's first variable made safe for a file name
     * (left out when there is none), and the output's number from 1.
     *
     * @throws BatchRefusedException 404 {@code batch_not_found}; 413
     *         {@code results_too_large} when the outputs total more than
     *         {@code godance.batches.results-zip-max}; both before anything is written
     */
    public Consumer<OutputStream> zip(UUID userId, UUID batchId) {
        RecipeBatch batch = batches.findByIdAndUserId(batchId, userId).orElseThrow(BatchRefusedException::notFound);
        long bytes = rows.sumOutputBytes(batchId);
        long max = props.resultsZipMax().toBytes();
        if (bytes > max) {
            throw BatchRefusedException.resultsTooLarge(bytes, max, "/v1/recipe-batches/" + batchId + "/results.csv");
        }
        return out -> {
            try {
                writeZip(batch, out);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        };
    }

    private void writeZip(RecipeBatch batch, OutputStream out) throws IOException {
        String first = batch.getVariableNames().isEmpty() ? null : batch.getVariableNames().get(0);
        try (ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
            // Images and video are compressed already; deflating them again only costs time.
            zip.setLevel(Deflater.NO_COMPRESSION);
            int after = -1;
            while (true) {
                List<RecipeBatchRow> page = rows.findPage(batch.getId(), after, PAGE);
                if (page.isEmpty()) break;
                Map<UUID, List<GenerationJobOutput>> outputs = views.outputsOf(page);
                for (RecipeBatchRow row : page) {
                    List<GenerationJobOutput> stored = outputs.getOrDefault(row.getJobId(), List.of());
                    String value = first == null || row.getVariables() == null ? null : row.getVariables().get(first);
                    for (int n = 0; n < stored.size(); n++) {
                        addEntry(zip, entryName(row.getRowIndex(), value, n + 1, stored.get(n).getStorageKey()),
                                stored.get(n).getStorageKey());
                    }
                }
                after = page.get(page.size() - 1).getRowIndex();
                if (page.size() < PAGE) break;
            }
        }
    }

    /** One output, streamed from storage into the zip. An output that cannot be opened is left out. */
    private void addEntry(ZipOutputStream zip, String name, String storageKey) throws IOException {
        InputStream in;
        try {
            in = storage.open(storageKey);
        } catch (RuntimeException e) {
            log.warn("RecipeBatchResults: {} left out of a results zip: {}", name, e.getMessage());
            return;
        }
        try (in) {
            zip.putNextEntry(new ZipEntry(name));
            in.transferTo(zip);
            zip.closeEntry();
        }
    }

    static String entryName(int index, String value, int n, String storageKey) {
        int dot = storageKey.lastIndexOf('.');
        String ext = dot < 0 || dot < storageKey.lastIndexOf('/') ? "bin" : storageKey.substring(dot + 1);
        String part = safe(value);
        return index + (part.isEmpty() ? "" : "-" + part) + "-" + n + "." + ext;
    }

    /**
     * A value made safe for a file name on any system: letters and digits
     * of any script are kept, every run of anything else becomes one
     * hyphen, and the result is at most {@value #NAME_PART_MAX} characters.
     */
    static String safe(String value) {
        if (value == null) return "";
        String cleaned = value.strip().replaceAll("[^\\p{L}\\p{N}]+", "-");
        cleaned = cleaned.replaceAll("^-+", "").replaceAll("-+$", "");
        if (cleaned.length() > NAME_PART_MAX) {
            // Never cut between the two halves of one character.
            int end = Character.isHighSurrogate(cleaned.charAt(NAME_PART_MAX - 1)) ? NAME_PART_MAX - 1 : NAME_PART_MAX;
            cleaned = cleaned.substring(0, end).replaceAll("-+$", "");
        }
        return cleaned;
    }
}
