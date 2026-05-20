/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.appointmentnotifier.client;

import java.net.HttpURLConnection;

/**
 * Applies dynamic request headers (hospital name, provider identity, credentials) to an outgoing
 * HTTP connection. Callers depend on this abstraction, not on the concrete GP-backed
 * implementation.
 */
public interface ConnectionHeadersApplier {
	
	/**
	 * Writes all GP-backed headers onto {@code conn} before the request body is sent.
	 */
	void applyHeaders(HttpURLConnection conn);
}
