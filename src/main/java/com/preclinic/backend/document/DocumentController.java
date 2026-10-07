package com.preclinic.backend.document;

import java.time.Duration;
import java.util.List;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.preclinic.backend.common.Api;
import com.preclinic.backend.document.DocumentService.DocumentDto;
import com.preclinic.backend.file.FileService.StoredFile;

/** Patient documents: both roles attach and view them; only the doctor removes one. */
@RestController
@RequestMapping(Api.V1)
public class DocumentController {

	private final DocumentService documents;

	public DocumentController(DocumentService documents) {
		this.documents = documents;
	}

	@GetMapping("/patients/{patientId}/documents")
	public List<DocumentDto> list(@PathVariable long patientId) {
		return documents.list(patientId);
	}

	@PostMapping(path = "/patients/{patientId}/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	@ResponseStatus(HttpStatus.CREATED)
	public DocumentDto upload(@PathVariable long patientId, @RequestParam("file") MultipartFile file,
			@RequestParam(defaultValue = "Other") String docType) {
		return documents.upload(patientId, docType, file);
	}

	/** The file itself, shown inline (images and PDFs only; never sniffed). */
	@GetMapping("/documents/{id}/file")
	public ResponseEntity<byte[]> file(@PathVariable long id) {
		StoredFile file = documents.download(id);
		return ResponseEntity.ok()
				.contentType(MediaType.parseMediaType(file.contentType()))
				.cacheControl(CacheControl.maxAge(Duration.ofMinutes(10)).cachePrivate())
				.header("X-Content-Type-Options", "nosniff")
				.header("Content-Disposition", "inline; filename=\"" + file.fileName().replace("\"", "") + "\"")
				.body(file.data());
	}

	@DeleteMapping("/documents/{id}")
	@PreAuthorize("hasRole('DOCTOR')")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void delete(@PathVariable long id) {
		documents.delete(id);
	}
}
