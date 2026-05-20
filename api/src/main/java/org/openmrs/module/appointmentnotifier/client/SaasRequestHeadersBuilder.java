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
import java.util.List;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.openmrs.api.AdministrationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import static org.openmrs.module.appointmentnotifier.AppointmentNotifierConstants.*;

/**
 * Reads dynamic request headers from OpenMRS global properties and applies them to an outgoing
 * connection. Provider-specific credential headers are delegated to the matching
 * {@link ProviderHeaderStrategy}, keeping this class closed to credential-format changes.
 */
@Component
public class SaasRequestHeadersBuilder implements ConnectionHeadersApplier {
	
	private static final Log log = LogFactory.getLog(SaasRequestHeadersBuilder.class);
	
	private final AdministrationService adminService;
	
	private final List<ProviderHeaderStrategy> strategies;
	
	@Autowired
	public SaasRequestHeadersBuilder(AdministrationService adminService, List<ProviderHeaderStrategy> strategies) {
		this.adminService = adminService;
		this.strategies = strategies;
	}
	
	/**
	 * Applies all GP-backed headers to {@code conn}: hospital name, messaging provider identity,
	 * and the provider-specific credential headers chosen by the active
	 * {@link ProviderHeaderStrategy}.
	 */
	@Override
	public void applyHeaders(HttpURLConnection conn) {
		String hospitalName = adminService.getGlobalProperty(GP_HOSPITAL_NAME, "Unknown Hospital");
		log.debug("Setting X-Hospital-Name header to: '" + hospitalName + "'");
		conn.setRequestProperty("X-Hospital-Name", hospitalName);

		String provider = adminService.getGlobalProperty(GP_MESSAGING_PROVIDER, DEFAULT_PROVIDER);
		conn.setRequestProperty("X-Messaging-Provider", provider);

		ProviderCredentials credentials = readCredentials();
		strategies.stream()
		        .filter(s -> s.supports(provider))
		        .findFirst()
		        .ifPresent(s -> s.applyHeaders(conn, credentials));
	}
	
	private ProviderCredentials readCredentials() {
		return new ProviderCredentials(adminService.getGlobalProperty(GP_MESSAGING_PROVIDER_TOKEN, ""),
		        adminService.getGlobalProperty(GP_MESSAGING_PROVIDER_USERNAME, ""), adminService.getGlobalProperty(
		            GP_MESSAGING_PROVIDER_PASSWORD, ""),
		        adminService.getGlobalProperty(GP_MESSAGING_PROVIDER_CLIENT_ID, ""), adminService.getGlobalProperty(
		            GP_MESSAGING_PROVIDER_CLIENT_SECRET, ""));
	}
}
