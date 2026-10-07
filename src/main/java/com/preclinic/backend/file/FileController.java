package com.preclinic.backend.file;

import java.time.Duration;

import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.preclinic.backend.common.Api;
import com.preclinic.backend.file.FileService.StoredFile;

@RestController
@RequestMapping(Api.V1 + "/files")
public class FileController {

	private final FileService files;

	public FileController(FileService files) {
		this.files = files;
	}

	/** Serves an uploaded file of the caller's clinic. Never sniffed, never executed by the browser. */
	@GetMapping("/{id}")
	public ResponseEntity<byte[]> get(@PathVariable long id) {
		StoredFile file = files.load(id);
		return ResponseEntity.ok()
				.contentType(MediaType.parseMediaType(file.contentType()))
				.cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePrivate())
				.header("X-Content-Type-Options", "nosniff")
				.header("Content-Disposition", "inline; filename=\"" + file.fileName().replace("\"", "") + "\"")
				.body(file.data());
	}
}
