package com.preclinic.backend.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.preclinic.backend.file.FileTypes;

class SecretCipherTest {

	private final SecretCipher cipher = new SecretCipher("unit-test-passphrase");

	@Test
	void roundTripsIncludingUnicode() {
		assertThat(cipher.decrypt(cipher.encrypt("token-123"))).isEqualTo("token-123");
		assertThat(cipher.decrypt(cipher.encrypt("پاسورڈ ✓"))).isEqualTo("پاسورڈ ✓");
	}

	@Test
	void usesAFreshIvSoEqualSecretsEncryptDifferently() {
		assertThat(cipher.encrypt("same")).isNotEqualTo(cipher.encrypt("same"));
	}

	@Test
	void aWrongKeyOrTamperedDataIsRejected() {
		String encrypted = cipher.encrypt("secret");
		assertThatThrownBy(() -> new SecretCipher("another-passphrase").decrypt(encrypted))
				.isInstanceOf(IllegalStateException.class);
		// Flip a character in the middle of the ciphertext (the tail can be base64 padding bits that
		// carry no data, which would make the "tampering" a no-op).
		char[] chars = encrypted.toCharArray();
		int middle = chars.length / 2;
		chars[middle] = chars[middle] == 'A' ? 'B' : 'A';
		assertThatThrownBy(() -> cipher.decrypt(new String(chars))).isInstanceOf(IllegalStateException.class);
	}

	@Test
	void fileTypesAreDetectedFromBytes() {
		assertThat(FileTypes.detect(new byte[] { (byte) 0x89, 'P', 'N', 'G', 1 })).contains(FileTypes.PNG);
		assertThat(FileTypes.detect(new byte[] { (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0 })).contains(FileTypes.JPEG);
		assertThat(FileTypes.detect("%PDF-1.7".getBytes())).contains(FileTypes.PDF);
		assertThat(FileTypes.detect("RIFF1234WEBPVP8 ".getBytes())).contains(FileTypes.WEBP);
		assertThat(FileTypes.detect("<script>alert(1)</script>".getBytes())).isEmpty();
		assertThat(FileTypes.detect(new byte[] { 1, 2 })).isEmpty();
	}
}
