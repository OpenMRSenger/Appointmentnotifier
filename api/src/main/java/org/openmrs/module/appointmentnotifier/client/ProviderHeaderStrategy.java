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
 * Strategy for applying provider-specific credential headers to an outgoing HTTP request.
 * <p>
 * Implement this interface and annotate with {@code @Component} to register a new provider without
 * modifying any existing class (Open/Closed Principle).
 */
public interface ProviderHeaderStrategy {
	
	/**
	 * @param providerName the value of the {@code appointmentnotifier.messagingProvider} global
	 *            property
	 * @return {@code true} when this strategy handles the given provider
	 */
	boolean supports(String providerName);
	
	/**
	 * Writes the appropriate credential headers onto {@code conn}. Headers for blank credential
	 * values must be omitted so the SaaS backend ignores them.
	 */
	void applyHeaders(HttpURLConnection conn, ProviderCredentials credentials);
}
