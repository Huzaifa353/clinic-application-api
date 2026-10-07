package com.preclinic.backend.document;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import com.preclinic.backend.common.ApiException;
import com.preclinic.backend.common.AuditService;
import com.preclinic.backend.file.FileService;
import com.preclinic.backend.file.FileService.StoredFile;
import com.preclinic.backend.patient.PatientService;
import com.preclinic.backend.security.CurrentUser;

/**
 * A patient's attachments (lab reports, X-rays, referrals ...). The bytes live in {@code file_asset};
 * this adds the patient link, the document type and who uploaded it. Content is checked from its bytes
 * (images and PDFs only, 10 MB).
 */
@Service
public class DocumentService {

	public static final Set<String> TYPES = Set.of("Lab Report", "X-Ray", "Ultrasound", "Prescription", "Referral", "Other");

	public record DocumentDto(long id, long patientId, String fileName, String docType, String contentType,
			long sizeBytes, String uploadedBy, java.time.Instant uploadedAt) {
	}

	private static final String SELECT = """
			select d.id, d.patient_id, d.file_name, d.doc_type, f.content_type, f.size_bytes, u.full_name as uploaded_by,
			       d.uploaded_at
			from patient_document d
			join file_asset f on f.id = d.file_id
			left join app_user u on u.id = d.uploaded_by
			""";

	private final JdbcClient jdbc;
	private final CurrentUser currentUser;
	private final PatientService patients;
	private final FileService files;
	private final AuditService audit;

	public DocumentService(JdbcClient jdbc, CurrentUser currentUser, PatientService patients, FileService files,
			AuditService audit) {
		this.jdbc = jdbc;
		this.currentUser = currentUser;
		this.patients = patients;
		this.files = files;
		this.audit = audit;
	}

	@Transactional(readOnly = true)
	public List<DocumentDto> list(long patientId) {
		patients.requireExists(patientId);
		return jdbc.sql(SELECT + " where d.patient_id = :p and d.clinic_id = :c order by d.uploaded_at desc, d.id desc")
				.param("p", patientId).param("c", currentUser.clinicId()).query((rs, i) -> toDto(rs)).list();
	}

	@Transactional
	public DocumentDto upload(long patientId, String docType, MultipartFile file) {
		patients.requireExists(patientId);
		if (docType == null || !TYPES.contains(docType)) {
			throw ApiException.badRequest("INVALID_DOCUMENT_TYPE", "The document type must be one of " + TYPES + ".");
		}
		long fileId = files.store(file, FileService.DOCUMENTS, FileService.MAX_DOCUMENT_BYTES);
		String name = jdbc.sql("select file_name from file_asset where id = :id").param("id", fileId)
				.query(String.class).single();
		long id = jdbc.sql("""
				insert into patient_document (clinic_id, patient_id, file_id, file_name, doc_type, uploaded_by)
				values (:c, :p, :f, :name, :type, :user) returning id
				""")
				.param("c", currentUser.clinicId()).param("p", patientId).param("f", fileId).param("name", name)
				.param("type", docType).param("user", currentUser.id()).query(Long.class).single();
		audit.log("document.upload", "patient_document", id, AuditService.detail("patientId", patientId, "type", docType));
		return get(id);
	}

	@Transactional(readOnly = true)
	public DocumentDto get(long id) {
		return jdbc.sql(SELECT + " where d.id = :id and d.clinic_id = :c")
				.param("id", id).param("c", currentUser.clinicId()).query((rs, i) -> toDto(rs)).optional()
				.orElseThrow(() -> ApiException.notFound("DOCUMENT_NOT_FOUND", "No such document."));
	}

	@Transactional(readOnly = true)
	public StoredFile download(long id) {
		Long fileId = jdbc.sql("select file_id from patient_document where id = :id and clinic_id = :c")
				.param("id", id).param("c", currentUser.clinicId()).query(Long.class).optional()
				.orElseThrow(() -> ApiException.notFound("DOCUMENT_NOT_FOUND", "No such document."));
		return files.load(fileId);
	}

	@Transactional
	public void delete(long id) {
		Long fileId = jdbc.sql("select file_id from patient_document where id = :id and clinic_id = :c")
				.param("id", id).param("c", currentUser.clinicId()).query(Long.class).optional()
				.orElseThrow(() -> ApiException.notFound("DOCUMENT_NOT_FOUND", "No such document."));
		jdbc.sql("delete from patient_document where id = :id").param("id", id).update();
		files.delete(fileId);
		audit.log("document.delete", "patient_document", id, AuditService.detail());
	}

	private static DocumentDto toDto(java.sql.ResultSet rs) throws java.sql.SQLException {
		return new DocumentDto(rs.getLong("id"), rs.getLong("patient_id"), rs.getString("file_name"),
				rs.getString("doc_type"), rs.getString("content_type"), rs.getLong("size_bytes"),
				rs.getString("uploaded_by"), rs.getObject("uploaded_at", OffsetDateTime.class).toInstant());
	}
}
