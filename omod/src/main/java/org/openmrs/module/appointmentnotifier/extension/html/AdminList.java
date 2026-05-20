/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.appointmentnotifier.extension.html;

import java.util.LinkedHashMap;
import java.util.Map;
import org.openmrs.module.web.extension.AdministrationSectionExt;
import org.openmrs.module.Extension;

/**
 * Registers the module against the org.openmrs.admin.list extension point. This causes the OpenMRS
 * web layer to mount the module's Spring MVC dispatcher so that
 * /openmrs/module/appointmentnotifier/*.form URLs are routed correctly.
 */
public class AdminList extends AdministrationSectionExt { // ← niet Extension

	@Override
	public String getTitle() {
		return "Appointment Notifier";
	}
	
	@Override
	public Map<String, String> getLinks() {
		Map<String, String> map = new LinkedHashMap<String, String>();
		map.put("module/appointmentnotifier/settings.form", "Settings");
		return map;
	}
}
