package com.garganttua.api.core.security.key;

import com.garganttua.api.commons.definition.IDomainKeyDefinition;
import com.garganttua.core.crypto.IKeyRealm;
import com.garganttua.core.reflection.ObjectAddress;

public record DomainKeyDefinition(
		ObjectAddress name,
		ObjectAddress keyAlgorithm,
		ObjectAddress signatureAlgorithm,
		ObjectAddress keyForSigning,
		ObjectAddress keyForSignatureVerification,
		ObjectAddress keyForEncryption,
		ObjectAddress keyForDecryption,
		ObjectAddress expiration,
		ObjectAddress revoked,
		ObjectAddress version,
		ObjectAddress rotate,
		IKeyRealm secretMaterialKek) implements IDomainKeyDefinition {

}
