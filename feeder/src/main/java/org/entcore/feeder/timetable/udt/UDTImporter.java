/*
 * Copyright © WebServices pour l'Éducation, 2016
 *
 * This file is part of ENT Core. ENT Core is a versatile ENT engine based on the JVM.
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation (version 3 of the License).
 *
 * For the sake of explanation, any module that communicate over native
 * Web protocols, such as HTTP, with ENT Core is outside the scope of this
 * license and could be license under its own terms. This is merely considered
 * normal use of ENT Core, and does not fall under the heading of "covered work".
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package org.entcore.feeder.timetable.udt;

import org.entcore.common.utils.StringUtils;
import org.entcore.feeder.timetable.AbstractTimetableImporter;
import org.entcore.feeder.timetable.Slot;
import org.entcore.feeder.utils.Report;
import org.joda.time.DateTime;
import org.vertx.java.core.AsyncResultHandler;
import org.vertx.java.core.json.JsonObject;

public class UDTImporter extends AbstractTimetableImporter {

	private int year;

	public UDTImporter() {
		super(uai);
	}

	@Override
	public void launch(AsyncResultHandler<Report> handler) throws Exception {

	}

	public void setYear(String year) {
		if (this.year == 0) {
			this.year = Integer.parseInt(year);
		}
	}

	public void initSchoolYear(JsonObject currentEntity) {
		startDateWeek1 = new DateTime(year, 1, 1, 0, 0).withWeekOfWeekyear(
				Integer.parseInt(currentEntity.getString("premiere_semaine_ISO")));
		slotDuration = Integer.parseInt(currentEntity.getString("duree_seq")) / 2;
	}

	public void initSchedule(JsonObject e) {
		final String slotKey = e.getString("code_jour") + StringUtils.padLeft(e.getString("code"), 2, '0') + e.getString("code_site");
		slots.put(slotKey, new Slot(e.getString("hre_deb"), e.getString("hre_fin"), slotDuration));
	}

	public void addRoom(JsonObject currentEntity) {
		rooms.put(currentEntity.getString("code"), currentEntity.getString("nom"));
	}

	@Override
	protected String getTeacherMappingAttribute() {
		return "externalId";
	}
}
