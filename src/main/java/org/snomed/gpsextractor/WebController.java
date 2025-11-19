package org.snomed.gpsextractor;

import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.nio.charset.StandardCharsets;

@RestController
public class WebController {

    @PostMapping("/api/filter")
    public ResponseEntity<InputStreamResource> filterTags(
            @RequestParam("file") MultipartFile file,
            @RequestParam("tags") String tags,
            @RequestParam(value = "activeOnly", defaultValue = "false") boolean activeOnly) throws IOException {

        if (file.isEmpty()) {
            throw new IllegalArgumentException("File cannot be empty");
        }

        String[] semanticTags = tags.split(",");
        for (int i = 0; i < semanticTags.length; i++) {
            semanticTags[i] = semanticTags[i].trim();
        }

        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8));
                BufferedWriter writer = new BufferedWriter(
                        new OutputStreamWriter(outputStream, StandardCharsets.UTF_8))) {

            ExtractSemanticTags.processStream(reader, writer, semanticTags, activeOnly);
        }

        ByteArrayInputStream inputStream = new ByteArrayInputStream(outputStream.toByteArray());
        InputStreamResource resource = new InputStreamResource(inputStream);

        String filename = "filtered_" + file.getOriginalFilename();

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=" + filename)
                .contentType(MediaType.parseMediaType("text/tab-separated-values"))
                .body(resource);
    }
}
