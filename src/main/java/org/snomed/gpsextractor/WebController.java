package org.snomed.gpsextractor;

import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.regex.Pattern;

@RestController
public class WebController {

    // ── Security constants ─────────────────────────────────────────────────────

    /**
     * Maximum number of semantic tags accepted in a single request.
     * Prevents excessive tag array allocations from a single large comma-separated value.
     */
    static final int MAX_SEMANTIC_TAGS = 100;

    /**
     * Maximum length of the raw {@code tags} query parameter.
     * Limits memory consumption from an unbounded parameter value.
     */
    static final int MAX_TAGS_PARAM_LENGTH = 4096;

    /**
     * Maximum length of a single semantic tag after splitting and trimming.
     * SNOMED semantic tags are short English words/phrases; 100 chars is generous.
     */
    static final int MAX_TAG_LENGTH = 100;

    /**
     * Allowlist pattern for safe characters in a semantic tag.
     * Only ASCII letters, digits, spaces, and hyphens are permitted — the same
     * characters that appear in real SNOMED semantic tags (e.g., "body structure",
     * "finding", "observable entity").
     */
    private static final Pattern SAFE_TAG_PATTERN = Pattern.compile("[a-zA-Z0-9 \\-]+");

    /**
     * Characters that must be stripped from a user-supplied filename before
     * embedding it in an HTTP header value.  Carriage return, newline, and
     * double-quote can all be used for HTTP response-splitting or header-injection
     * attacks.  Backslash is stripped for robustness.
     */
    private static final Pattern UNSAFE_FILENAME_CHARS = Pattern.compile("[\\r\\n\"\\\\]");

    // ── Endpoint ───────────────────────────────────────────────────────────────

    @PostMapping("/api/filter")
    public ResponseEntity<InputStreamResource> filterTags(
            @RequestParam("file") MultipartFile file,
            @RequestParam("tags") String tags,
            @RequestParam(value = "activeOnly", defaultValue = "false") boolean activeOnly)
            throws IOException {

        // ── Input validation ─────────────────────────────────────────────────

        if (file.isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(errorResource("File cannot be empty"));
        }

        if (tags == null || tags.isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(errorResource("tags parameter must not be empty"));
        }

        // Guard against an unbounded tags parameter causing excessive memory use
        if (tags.length() > MAX_TAGS_PARAM_LENGTH) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(errorResource("tags parameter exceeds maximum permitted length of "
                            + MAX_TAGS_PARAM_LENGTH + " characters"));
        }

        // Split, trim, and reject blanks
        String[] rawTags = tags.split(",", -1);
        if (rawTags.length > MAX_SEMANTIC_TAGS) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(errorResource("Too many semantic tags: maximum is " + MAX_SEMANTIC_TAGS));
        }

        String[] semanticTags = Arrays.stream(rawTags)
                .map(String::trim)
                .toArray(String[]::new);

        for (String tag : semanticTags) {
            if (tag.isEmpty()) {
                return ResponseEntity.badRequest()
                        .body(errorResource("Semantic tags must not be blank after trimming"));
            }
            if (tag.length() > MAX_TAG_LENGTH) {
                return ResponseEntity.badRequest()
                        .body(errorResource("Semantic tag exceeds maximum length of "
                                + MAX_TAG_LENGTH + " characters: '" + tag + "'"));
            }
            // Enforce character allowlist to prevent injection through tag values
            if (!SAFE_TAG_PATTERN.matcher(tag).matches()) {
                return ResponseEntity.badRequest()
                        .body(errorResource("Semantic tag contains invalid characters: '" + tag
                                + "'. Only letters, digits, spaces, and hyphens are permitted."));
            }
        }

        // ── Processing ───────────────────────────────────────────────────────

        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        int recordCount;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8));
                BufferedWriter writer = new BufferedWriter(
                        new OutputStreamWriter(outputStream, StandardCharsets.UTF_8))) {

            recordCount = ExtractSemanticTags.processStream(reader, writer, semanticTags, activeOnly);
        }

        // ── Response ─────────────────────────────────────────────────────────

        ByteArrayInputStream inputStream = new ByteArrayInputStream(outputStream.toByteArray());
        InputStreamResource resource = new InputStreamResource(inputStream);

        // SEC: Sanitise the filename before placing it in the Content-Disposition header.
        // getOriginalFilename() is attacker-controlled; CR, LF, and '"' can be used for
        // HTTP response-splitting / header-injection.  Strip all unsafe characters, then
        // quote the result per RFC 6266 so that spaces and other special characters do not
        // break the header value.
        String rawFilename = file.getOriginalFilename();
        String safeFilename = sanitiseFilename(rawFilename);
        String contentDisposition = "attachment; filename=\"" + safeFilename + "\"";

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, contentDisposition)
                .header("X-Record-Count", String.valueOf(recordCount))
                // SEC: Prevent MIME-type sniffing by the browser
                .header("X-Content-Type-Options", "nosniff")
                // SEC: Deny framing to guard against clickjacking
                .header("X-Frame-Options", "DENY")
                .contentType(MediaType.parseMediaType("text/tab-separated-values"))
                .body(resource);
    }

    // ── Package-private helpers (tested directly) ──────────────────────────────

    /**
     * Sanitises an attacker-controlled filename for safe use in an HTTP header.
     *
     * <ol>
     *   <li>Strips path separators so that a filename like {@code ../../etc/passwd}
     *       cannot be re-interpreted as a path by the browser.</li>
     *   <li>Strips characters that can be used for header-injection
     *       (CR, LF, double-quote, backslash).</li>
     *   <li>Falls back to {@code "filtered_output.tsv"} if the result is empty.</li>
     * </ol>
     *
     * The caller is responsible for wrapping the return value in {@code "..."} in the
     * Content-Disposition header value (as required by RFC 6266).
     *
     * @param rawFilename the filename as returned by {@link MultipartFile#getOriginalFilename()}
     * @return a safe filename containing no header-injection characters
     */
    static String sanitiseFilename(String rawFilename) {
        if (rawFilename == null || rawFilename.isBlank()) {
            return "filtered_output.tsv";
        }
        // Truncate at the first CR or LF: everything after the newline is attacker-controlled
        // header content, not part of the filename (prevents header-injection via CRLF).
        int crIdx = rawFilename.indexOf('\r');
        int lfIdx = rawFilename.indexOf('\n');
        int cutAt = rawFilename.length();
        if (crIdx != -1) cutAt = Math.min(cutAt, crIdx);
        if (lfIdx != -1) cutAt = Math.min(cutAt, lfIdx);
        String name = rawFilename.substring(0, cutAt);
        // Strip path separators (prevent path traversal semantics in Content-Disposition)
        name = name.replace("/", "").replace("\\", "");
        // Strip path traversal sequences that survive separator removal (e.g. "....etcpasswd")
        name = name.replace("..", "_");
        // Strip the double-quote character that would break the quoted header value
        name = name.replace("\"", "_");
        // Prepend prefix so the download is clearly identified
        name = "filtered_" + name;
        // Final fallback guard
        if (name.isBlank() || name.equals("filtered_")) {
            return "filtered_output.tsv";
        }
        return name;
    }

    /** Builds a minimal error body as an {@link InputStreamResource}. */
    private static InputStreamResource errorResource(String message) {
        byte[] bytes = message.getBytes(StandardCharsets.UTF_8);
        return new InputStreamResource(new ByteArrayInputStream(bytes));
    }
}
