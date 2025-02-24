/* Copyright (C) 2011 [Gobierno de Espana]
 * This file is part of "Cliente @Firma".
 * "Cliente @Firma" is free software; you can redistribute it and/or modify it under the terms of:
 *   - the GNU General Public License as published by the Free Software Foundation;
 *     either version 2 of the License, or (at your option) any later version.
 *   - or The European Software License; either version 1.1 or (at your option) any later version.
 * You may contact the copyright holder at: soporte.afirma@seap.minhap.es
 */

package es.gob.afirma.signers.batch.json;

import java.io.IOException;
import java.security.cert.X509Certificate;
import java.util.Properties;
import java.util.logging.Level;

import es.gob.afirma.core.signers.TriphaseData;
import es.gob.afirma.signers.batch.BatchException;
import es.gob.afirma.signers.batch.ProcessResult;
import es.gob.afirma.triphase.server.document.BatchDocumentManager;

/** Lote de firmas electr&oacute;nicas que se ejecuta secuencialmente. */
public final class JSONSignBatchSerial extends JSONSignBatch {

	/**
	 * Crea un lote de firmas que se ejecuta secuencialmente.
	 * @param json JSON de definici&oacute;n de lote.
	 * @throws IOException Si hay problemas en la creaci&oacute;n del lote.
	 * */
	public JSONSignBatchSerial(final byte[] json) throws IOException {
		super(json);
	}

	@Override
	public String doPreBatch(final X509Certificate[] certChain) throws BatchException {

		boolean ignoreRemaining = false;
		final StringBuilder sb = new StringBuilder("{"); //$NON-NLS-1$
		sb.append("\"format\":\"" + this.format + "\",");  //$NON-NLS-1$//$NON-NLS-2$
		sb.append("\"signs\": ["); //$NON-NLS-1$
		for (int i = 0 ; i < this.signs.size() ; i++) {
			final JSONSingleSign ss = this.signs.get(i);
			if (ignoreRemaining) {
				ss.setProcessResult(ProcessResult.PROCESS_RESULT_SKIPPED);
				continue;
			}
			final String tmp;
			try {
				tmp = ss.doPreProcess(certChain, this.algorithm, this.documentManager, this.docCacheManager);
			}
			catch(final Exception e) {
				ss.setProcessResult(new ProcessResult(ProcessResult.Result.ERROR_PRE, e.toString()));
				if (this.stopOnError) {
					ignoreRemaining = true;
					LOGGER.log(Level.WARNING,
							"Error en una de las firmas del lote (" + ss.getId() + "), se ignoraran el resto de elementos: " + e //$NON-NLS-1$ //$NON-NLS-2$
							, e);
				}
				else {
					LOGGER.log(Level.WARNING,
							"Error en una de las firmas del lote (" + ss.getId() + "), se continua con el siguiente elemento: " + e //$NON-NLS-1$ //$NON-NLS-2$
							, e);
				}
				continue;
			}
			sb.append(tmp);
			if (this.signs.size() -1 != i) {
				sb.append(","); //$NON-NLS-1$
			}
		}
		sb.append("]}"); //$NON-NLS-1$

		return sb.toString();
	}

	@Override
	public String doPostBatch(final X509Certificate[] certChain,
			                  final TriphaseData td) {

		if (td == null) {
			throw new IllegalArgumentException("Los datos de sesion trifasica no pueden ser nulos"); //$NON-NLS-1$
		}

		boolean ignoreRemaining = false;
		boolean error = false;

		for (final JSONSingleSign ss : this.signs) {

			// Si se ha detectado un error y no deben procesarse el resto de firmas, se marcan como tal
			if (ignoreRemaining) {
				ss.setProcessResult(ProcessResult.PROCESS_RESULT_SKIPPED);
				continue;
			}

			// Si no se encuentran firmas con ese identificador, es que fallaron en la prefirma
			if (td.getTriSigns(ss.getId()) == null) {
				error = true;
				if (this.stopOnError) {
					LOGGER.warning("Se detecto un error previo en la firma, se ignoraran el resto de elementos"); //$NON-NLS-1$
					ignoreRemaining = true;
				}
				else {
					LOGGER.warning("Se detecto un error previo en la firma, se continua con el resto de elementos"); //$NON-NLS-1$
				}
				ss.setProcessResult(new ProcessResult(ProcessResult.Result.ERROR_PRE, "Error en la prefirma")); //$NON-NLS-1$
				continue;
			}

			try {
				ss.doPostProcess(
					certChain,
					td,
					this.algorithm,
					getId(),
					this.documentManager,
					this.docCacheManager
				);
			}
			catch (final Exception e) {

				error = true;

				final ProcessResult.Result resultado;
				if (e instanceof AOSaveDataException) {
					resultado = ProcessResult.Result.DONE_BUT_ERROR_SAVING;
				} else {
					resultado = ProcessResult.Result.ERROR_POST;
				}

				ss.setProcessResult(new ProcessResult(resultado, e.toString()));

				if (this.stopOnError) {
					LOGGER.log(
							Level.SEVERE,
							"Error en una de las firmas del lote (" + ss.getId() + "), se parara el proceso: " + e, //$NON-NLS-1$ //$NON-NLS-2$
							e
							);
					ignoreRemaining = true;
				}
				else {
					LOGGER.severe(
							"Error en una de las firmas del lote (" + ss.getId() + "), se continua con el siguiente elemento: " + e //$NON-NLS-1$ //$NON-NLS-2$
							);
				}
				continue;
			}

			ss.setProcessResult(ProcessResult.PROCESS_RESULT_DONE_SAVED);
		}

		// Tenemos los datos subidos, ahora hay que, si hubo error, deshacer
		// los que se subiesen antes del error si se indico parar en error
		if (error && this.stopOnError) {
			for (final JSONSingleSign ss : this.signs) {
				if (ss.getProcessResult().wasSaved()) {

					if (BatchDocumentManager.class.isAssignableFrom(this.documentManager.getClass())) {
						final Properties singleSignProps = new Properties();
						singleSignProps.put("format", ss.getSignFormat().toString()); //$NON-NLS-1$
						try {
							((BatchDocumentManager) this.documentManager).rollback(ss.getDataRef(), certChain, singleSignProps);
						} catch (final IOException e) {
							LOGGER.severe(
									"No se pudo deshacer el guardado de una firma (" + ss.getId() + ") despues de la cancelacion del lote: " + e //$NON-NLS-1$ //$NON-NLS-2$
								);
						}
					}

					ss.setProcessResult(ProcessResult.PROCESS_RESULT_ROLLBACKED);
				}
			}
		}

		deleteAllTemps();

		return getResultLog();

	}
}
