/* Copyright (C) 2011 [Gobierno de Espana]
 * This file is part of "Cliente @Firma".
 * "Cliente @Firma" is free software; you can redistribute it and/or modify it under the terms of:
 *   - the GNU General Public License as published by the Free Software Foundation;
 *     either version 2 of the License, or (at your option) any later version.
 *   - or The European Software License; either version 1.1 or (at your option) any later version.
 * You may contact the copyright holder at: soporte.afirma@seap.minhap.es
 */

package es.gob.afirma.signvalidation;

import java.io.IOException;
import java.security.cert.CertificateExpiredException;
import java.security.cert.CertificateNotYetValidException;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.logging.Logger;

import com.aowagie.text.pdf.AcroFields;
import com.aowagie.text.pdf.PdfDictionary;
import com.aowagie.text.pdf.PdfName;
import com.aowagie.text.pdf.PdfPKCS7;
import com.aowagie.text.pdf.PdfReader;

import es.gob.afirma.signvalidation.SignValidity.SIGN_DETAIL_TYPE;
import es.gob.afirma.signvalidation.SignValidity.VALIDITY_ERROR;

/** Validador de firmas PDF.
 * Se validan los certificados en local revisando si procede las fechas de validez de los certificados.
 * @author Tom&aacute;s Garc&iacute;a-Mer&aacute;s. */
public final class ValidatePdfSignature implements SignValider{

	private static final Logger LOGGER = Logger.getLogger("es.gob.afirma"); //$NON-NLS-1$

	private static final PdfName PDFNAME_ETSI_RFC3161 = new PdfName("ETSI.RFC3161"); //$NON-NLS-1$
	private static final PdfName PDFNAME_DOCTIMESTAMP = new PdfName("DocTimeStamp"); //$NON-NLS-1$

	/** Valida una firma PDF (PKCS#7/PAdES).
	 * De los certificados de firma se revisan &uacute;nicamente las fechas de validez.
     * @param sign PDF firmado.
     * @return Validez de la firma.
     * @throws IOException Si ocurren problemas relacionados con la lectura del documento
     * o si no se encuentran firmas PDF en el documento. */
	@Override
	public SignValidity validate(final byte[] sign) throws IOException {
		return validate(sign, true);
	}

	/** Valida una firma PDF (PKCS#7/PAdES).
	 * De los certificados de firma se revisan &uacute;nicamente las fechas de validez.
     * @param sign PDF firmado.
     * @param checkCertificates Indica si debe comprobarse la caducidad de los certificados de firma.
     * @return Validez de la firma.
     * @throws IOException Si ocurren problemas relacionados con la lectura del documento
     * o si no se encuentran firmas PDF en el documento. */
	@Override
	public SignValidity validate(final byte[] sign, final boolean checkCertificates) throws IOException {
		AcroFields af;
		try {
			final PdfReader reader = new PdfReader(sign);
			af = reader.getAcroFields();
		}
		catch (final Exception e) {
			return new SignValidity(SIGN_DETAIL_TYPE.KO, VALIDITY_ERROR.NO_SIGN);
		}

		final List<String> sigNames = af.getSignatureNames();

		if (sigNames.size() == 0) {
			return new SignValidity(SIGN_DETAIL_TYPE.KO, VALIDITY_ERROR.NO_SIGN);
		}

		for (final String name : sigNames) {
			final PdfPKCS7 pk = af.verifySignature(name);

    		// Comprobamos si es una firma o un sello
    		final PdfDictionary pdfDictionary = af.getSignatureDictionary(name);

    		// En los sellos no comprobamos el PKCS#1
    		if (!PDFNAME_ETSI_RFC3161.equals(pdfDictionary.get(PdfName.SUBFILTER)) && !PDFNAME_DOCTIMESTAMP.equals(pdfDictionary.get(PdfName.SUBFILTER))) {
				try {
					if (!pk.verify()) {
						return new SignValidity(SIGN_DETAIL_TYPE.KO, VALIDITY_ERROR.NO_MATCH_DATA);
					}
				}
				catch (final Exception e) {
					LOGGER.warning("Error validando una de las firmas del PDF: " + e); //$NON-NLS-1$
					return new SignValidity(SIGN_DETAIL_TYPE.KO, VALIDITY_ERROR.CORRUPTED_SIGN, e);
				}
    		}
    		if (checkCertificates) {
				final X509Certificate signCert = pk.getSigningCertificate();
				try {
					signCert.checkValidity();
				}
				catch (final CertificateExpiredException e) {
					// Certificado caducado
			        return new SignValidity(SIGN_DETAIL_TYPE.KO, VALIDITY_ERROR.CERTIFICATE_EXPIRED, e);
				}
				catch (final CertificateNotYetValidException e) {
					// Certificado aun no valido
			        return new SignValidity(SIGN_DETAIL_TYPE.KO, VALIDITY_ERROR.CERTIFICATE_NOT_VALID_YET, e);
				}
			}
		}
		return new SignValidity(SIGN_DETAIL_TYPE.OK, null);
	}

}
