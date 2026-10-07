package com.preclinic.backend.file;

import java.io.IOException;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import com.preclinic.backend.common.ApiException;
import com.preclinic.backend.security.CurrentUser;

/** Stores and serves uploaded binaries ({@code file_asset}). Content type is detected, not trusted. */
@Service
public class FileService {

	public static final Set<String> IMAGES = Set.of(FileTypes.PNG, FileTypes.JPEG, FileTypes.WEBP);
	public static final Set<String> IMAGES_AND_ICO = Set.of(FileTypes.PNG, FileTypes.JPEG, FileTypes.WEBP,
			FileTypes.ICO);
	public static final Set<String> DOCUMENTS = Set.of(FileTypes.PNG, FileTypes.JPEG, FileTypes.WEBP,
			FileTypes.PDF);
	public static final long MAX_IMAGE_BYTES = 2L * 1024 * 1024;
	public static final long MAX_DOCUMENT_BYTES = 10L * 1024 * 1024;

	public record StoredFile(String fileName, String contentType, byte[] data) {
	}

	private final JdbcClient jdbc;
	private final CurrentUser currentUser;

	public FileService(JdbcClient jdbc, CurrentUser currentUser) {
		this.jdbc = jdbc;
		this.currentUser = currentUser;
	}

	/** Validates and stores an upload for the caller's clinic; returns the new file id. */
	@Transactional
	public long store(MultipartFile upload, Set<String> allowedTypes, long maxBytes) {
		if (upload == null || upload.isEmpty()) {
			throw ApiException.badRequest("FILE_REQUIRED", "A file is required.");
		}
		if (upload.getSize() > maxBytes) {
			throw new ApiException(HttpStatus.CONTENT_TOO_LARGE, "FILE_TOO_LARGE",
					"The file is larger than " + (maxBytes / (1024 * 1024)) + " MB.");
		}
		byte[] bytes;
		try {
			bytes = upload.getBytes();
		}
		catch (IOException e) {
			throw ApiException.badRequest("FILE_UNREADABLE", "The uploaded file could not be read.");
		}
		String contentType = FileTypes.detect(bytes)
				.filter(allowedTypes::contains)
				.orElseThrow(() -> ApiException.unprocessable("FILE_TYPE_NOT_ALLOWED",
						"This file type is not allowed."));
		String name = sanitizeName(upload.getOriginalFilename());
		return jdbc.sql("""
				insert into file_asset (clinic_id, file_name, content_type, size_bytes, data, created_by)
				values (:clinic, :name, :type, :size, :data, :user)
				returning id
				""")
				.param("clinic", currentUser.clinicId())
				.param("name", name)
				.param("type", contentType)
				.param("size", (long) bytes.length)
				.param("data", bytes)
				.param("user", currentUser.id())
				.query(Long.class).single();
	}

	@Transactional(readOnly = true)
	public StoredFile load(long fileId) {
		return jdbc.sql("select file_name, content_type, data from file_asset where id = :id and clinic_id = :clinic")
				.param("id", fileId)
				.param("clinic", currentUser.clinicId())
				.query((rs, i) -> new StoredFile(rs.getString("file_name"), rs.getString("content_type"),
						rs.getBytes("data")))
				.optional()
				.orElseThrow(() -> ApiException.notFound("FILE_NOT_FOUND", "No such file."));
	}

	/** Removes a file no longer referenced by anything (callers clear their reference first). */
	@Transactional
	public void delete(Long fileId) {
		if (fileId != null) {
			jdbc.sql("delete from file_asset where id = :id and clinic_id = :clinic")
					.param("id", fileId).param("clinic", currentUser.clinicId()).update();
		}
	}

	private static String sanitizeName(String original) {
		if (original == null || original.isBlank()) {
			return "upload";
		}
		String name = original.replace('\\', '/');
		name = name.substring(name.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}]", "").trim();
		if (name.isEmpty()) {
			return "upload";
		}
		return name.length() > 200 ? name.substring(0, 200) : name;
	}
}
