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

import org.entcore.feeder.timetable.AbstractTimetableImporter;
import org.entcore.feeder.timetable.Slot;
import org.entcore.feeder.utils.JsonUtil;
import org.entcore.feeder.utils.Report;
import org.joda.time.DateTime;
import org.vertx.java.core.AsyncResultHandler;
import org.vertx.java.core.json.JsonArray;
import org.vertx.java.core.json.JsonObject;
import org.xml.sax.InputSource;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.XMLReaderFactory;

import java.io.FileInputStream;
import java.util.UUID;

import static fr.wseduc.webutils.Utils.getOrElse;
import static org.entcore.common.utils.StringUtils.isEmpty;
import static org.entcore.common.utils.StringUtils.padLeft;
import static org.entcore.feeder.dictionary.structures.DefaultProfiles.TEACHER_PROFILE_EXTERNAL_ID;

public class UDTImporter extends AbstractTimetableImporter {

	private static final String STUDENTS_TO_GROUPS =
			"MATCH (u:User {externalId = {epj}}), (fg:FunctionalGroup {externalId:{externalId}}) " +
			"MERGE u-[:IN {source:{source}, inDate:{inDate}, outDate:{outDate}}]->fg ";
	public static final String UDT = "UDT";
	public static final String CODE = "code";
	private int year;

	public UDTImporter(String uai, String acceptLanguage) {
		super(uai, acceptLanguage);
	}

	@Override
	public void launch(AsyncResultHandler<Report> handler) throws Exception {
		final String basePath = "/home/dboissin/Docs/EDT - UDT/UDT20160908/";
		parse(basePath + "UDCal_24.xml");
		parse(basePath + "UDCal_00.xml");
		parse(basePath + "UDCal_03.xml");
		parse(basePath + "UDCal_04.xml");
		parse(basePath + "UDCal_05.xml");
		parse(basePath + "UDCal_07.xml");
		parse(basePath + "UDCal_08.xml");
		parse(basePath + "UDCal_19.xml");
		parse(basePath + "UDCal_21.xml");
		parse(basePath + "UDCal_13.xml");
	}

	private void parse(String filePath) throws Exception {
		InputSource in = new InputSource(new FileInputStream(filePath));
		UDTHandler sh = new UDTHandler(this);
		XMLReader xr = XMLReaderFactory.createXMLReader();
		xr.setContentHandler(sh);
		xr.parse(in);
	}

	void setYear(String year) {
		if (this.year == 0) {
			this.year = Integer.parseInt(year);
		}
	}

	void initSchoolYear(JsonObject currentEntity) {
		startDateWeek1 = new DateTime(year, 1, 1, 0, 0).withWeekOfWeekyear(
				Integer.parseInt(currentEntity.getString("premiere_semaine_ISO")));
		slotDuration = Integer.parseInt(currentEntity.getString("duree_seq")) / 2;
	}

	void initSchedule(JsonObject e) {
		final String slotKey = e.getString("code_jour") + padLeft(e.getString(CODE), 2, '0') + e.getString("code_site");
		slots.put(slotKey, new Slot(e.getString("hre_deb"), e.getString("hre_fin"), slotDuration));
	}

	void addRoom(JsonObject currentEntity) {
		rooms.put(currentEntity.getString(CODE), currentEntity.getString("nom"));
	}

	@Override
	protected String getTeacherMappingAttribute() {
		return "externalId";
	}

	@Override
	protected String getSource() {
		return UDT;
	}

	void addProfesseur(JsonObject currentEntity) {
		try {
			final String id = currentEntity.getString(CODE);
			String externalId = currentEntity.getString("epj");
			JsonObject p = null;
			if (isEmpty(externalId)) {
				p = persEducNat.applyMapping(currentEntity);
				p.putArray("profiles", new JsonArray().addString("Teacher"));
				externalId = JsonUtil.checksum(p);
			}
			final String teacherId = teachersMapping.get(externalId);
			if (teacherId != null) {
				teachers.put(id, teacherId);
			} else {
				if (p == null) {
					p = persEducNat.applyMapping(currentEntity);
					p.putArray("profiles", new JsonArray().addString("Teacher"));
				}
				p.putString("externalId", externalId);
				final String userId = UUID.randomUUID().toString();
				p.putString("id", userId);
				persEducNat.createOrUpdatePersonnel(p, TEACHER_PROFILE_EXTERNAL_ID, structure, null, null, true, true);
				teachers.put(id, userId);
			}
		} catch (Exception e) {
			report.addError(e.getMessage());
		}
	}

	void addSubject(JsonObject s) {
		final String code = s.getString(CODE);
		super.addSubject(code, new JsonObject().putString("Code", code).putString("Libelle", s.getString("libelle")));
	}

	void addClasse(JsonObject currentEntity) {
		final String id = currentEntity.getString(CODE);
		classes.put(id, currentEntity);
		final String ocn = currentEntity.getString("libelle");
		final String className = (classesMapping != null) ? getOrElse(classesMapping.getString(ocn), ocn, false) : ocn;
		currentEntity.putString("className", className);
		txXDT.add(UNKNOWN_CLASSES, new JsonObject().putString("UAI", UAI).putString("className", className));
	}

	void addGroup(JsonObject currentEntity) {
		final String id = currentEntity.getString("code_div") + currentEntity.getString(CODE);
		groups.put(id, currentEntity);
		String name = currentEntity.getString("code_sts");
		if (isEmpty(name)) {
			name = id;
		}
		txXDT.add(CREATE_GROUPS, new JsonObject().putString("structureExternalId", structureExternalId).putString("name", name)
				.putString("externalId", structureExternalId + "$" + name).putString("id", UUID.randomUUID().toString()));
	}

	void addEleve(JsonObject currentEntity) {

	}

}
