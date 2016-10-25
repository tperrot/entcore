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

import fr.wseduc.swift.storage.DefaultAsyncResult;
import org.entcore.feeder.timetable.AbstractTimetableImporter;
import org.entcore.feeder.timetable.Slot;
import org.entcore.feeder.utils.JsonUtil;
import org.entcore.feeder.utils.Report;
import org.joda.time.DateTime;
import org.joda.time.format.DateTimeFormat;
import org.vertx.java.core.AsyncResult;
import org.vertx.java.core.AsyncResultHandler;
import org.vertx.java.core.Handler;
import org.vertx.java.core.Vertx;
import org.vertx.java.core.json.JsonArray;
import org.vertx.java.core.json.JsonObject;
import org.xml.sax.InputSource;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.XMLReaderFactory;

import java.io.FileInputStream;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static fr.wseduc.webutils.Utils.getOrElse;
import static fr.wseduc.webutils.Utils.isNotEmpty;
import static org.entcore.common.utils.StringUtils.isEmpty;
import static org.entcore.common.utils.StringUtils.padLeft;
import static org.entcore.feeder.dictionary.structures.DefaultProfiles.TEACHER_PROFILE_EXTERNAL_ID;

public class UDTImporter extends AbstractTimetableImporter {

	private static final String STUDENTS_TO_GROUPS =
			"MATCH (u:User {externalId = {epj}}), (fg:FunctionalGroup {externalId:{externalId}}) " +
			"MERGE u-[:IN {source:{source}, inDate:{inDate}, outDate:{outDate}}]->fg ";
	public static final String UDT = "UDT";
	public static final String CODE = "code";
	private static final Pattern filenameWeekPatter = Pattern.compile("UDCal_[0-9]{2}_([0-9]{2})\\.xml$");
	private int year;
	private long endStudents;
	private Map<String, Set<String>> coens = new HashMap<>();
	private Map<String, JsonObject> fichesT = new HashMap<>();
	private Map<String, String> regroup = new HashMap<>();
	private Map<String, List<JsonObject>> lfts = new HashMap<>();
	private HashMap<Integer, Integer> periods = new HashMap<>(); // key : start, value : end period
	private Vertx vertx;

	public UDTImporter(Vertx vertx, String uai, String acceptLanguage) {
		super(uai, acceptLanguage);
		this.vertx = vertx;
	}

	@Override
	public void launch(final AsyncResultHandler<Report> handler) throws Exception {
		final String basePath = "/home/dboissin/Docs/EDT - UDT/UDT20160908/";
		parse(basePath + "UDCal_24.xml");
		parse(basePath + "semaines.xml");
		parse(basePath + "UDCal_00.xml");
		parse(basePath + "UDCal_03.xml");
		parse(basePath + "UDCal_04.xml");
		parse(basePath + "UDCal_05.xml");
		parse(basePath + "UDCal_07.xml");
		parse(basePath + "UDCal_08.xml");
		parse(basePath + "UDCal_19.xml");
		parse(basePath + "UDCal_13.xml");
		parse(basePath + "UDCal_21.xml");
		parse(basePath + "UDCal_23.xml");
		parse(basePath + "UDCal_11.xml");
		parse(basePath + "UDCal_12.xml");
		generateCourses(startDateWeek1.getWeekOfWeekyear());
		vertx.fileSystem().readDir(basePath, "UDCal_12_[0-9]+.xml", new Handler<AsyncResult<String[]>>() {
			@Override
			public void handle(AsyncResult<String[]> event) {
				if (event.succeeded() && event.result().length > 0) {
					try {
						for (String p : event.result()) {
							parse(p);
							Matcher m = filenameWeekPatter.matcher(p);
							if (m.find()) {
								generateCourses(Integer.parseInt(m.group(1)));
							}
						}
						commit(handler);
					} catch (Exception e) {
						handler.handle(new DefaultAsyncResult<Report>(e));
					}
				}
			}
		});
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

	public void setEndStudents(String endStudents) {
		this.endStudents = DateTime.parse(endStudents, DateTimeFormat.forPattern("dd/MM/yyyy")).getMillis();
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

	void initPeriods(JsonObject currentEntity) {
		JsonArray weeks = currentEntity.getArray("semaine");
		if (weeks != null && weeks.size() > 0) {
			int oldWeek = startDateWeek1.getWeekOfWeekyear();
			for (Object o : weeks) {
				if (o instanceof JsonObject) {
					int week = Integer.parseInt(((JsonObject) o).getString("num"));
					if (week != oldWeek) {
						periods.put(oldWeek, week - 1);
						oldWeek = week;
					}
				}
			}
			periods.put(oldWeek, new DateTime(endStudents).getWeekOfWeekyear());
		}
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
//		String name = currentEntity.getString("code_sts");
		String name = currentEntity.getString("code_div") + " Gr " + currentEntity.getString(CODE);
		if (isEmpty(name)) {
			name = id;
		}
		txXDT.add(CREATE_GROUPS, new JsonObject().putString("structureExternalId", structureExternalId).putString("name", name)
				.putString("externalId", structureExternalId + "$" + name).putString("id", UUID.randomUUID().toString()));
	}

	void addGroup2(JsonObject currentEntity) {
		final String codeGroup = currentEntity.getString("code_gpe");
		final String name = currentEntity.getString("nom");
		if (isNotEmpty(codeGroup)) {
			final String groupId = currentEntity.getString("code_div") + codeGroup;
			JsonObject group = groups.get(groupId);
			if (group == null) {
				report.addError("unknown.group.mapping");
				return;
			}
			JsonArray groups = group.getArray("groups");
			if (groups == null) {
				groups = new JsonArray();
				group.putArray("groups", groups);
			}
			groups.add(name);
		} else {
			final String classId = currentEntity.getString("code_div");
			JsonObject classe = classes.get(classId);
			if (classe == null) {
				report.addError("unknown.class.mapping");
				return;
			}
			JsonArray groups = classe.getArray("groups");
			if (groups == null) {
				groups = new JsonArray();
				classe.putArray("groups", groups);
			}
			groups.add(name);
		}
		regroup.put(currentEntity.getString(CODE), name);
		txXDT.add(CREATE_GROUPS, new JsonObject().putString("structureExternalId", structureExternalId).putString("name", name)
				.putString("externalId", structureExternalId + "$" + name).putString("id", UUID.randomUUID().toString()));
	}

	void addEleve(JsonObject currentEntity) {
		final String epj = currentEntity.getString("epj");
		if (isEmpty(epj)) {
			report.addError("invalid.epj");
			return;
		}
		final String codeGroup = currentEntity.getString("gpe");
		final String codeDiv = currentEntity.getString("code_div");
		JsonArray groups;
		if (isNotEmpty(codeGroup)) {
			JsonObject group = this.groups.get(codeDiv + codeGroup);
			if (group == null) {
				report.addError("unknown.group.mapping");
				return;
			}
			//final String name = group.getString("code_sts");
			final String name = group.getString("code_div") + " Gr " + group.getString(CODE);
			txXDT.add(STUDENTS_TO_GROUPS, new JsonObject()
					.putString("epj", epj)
					.putString("externalId", structureExternalId + "$" + name)
					.putString("source", UDT)
					.putNumber("inDate", importTimestamp)
					.putNumber("outDate", endStudents));
			groups = group.getArray("groups");

		} else {
			JsonObject classe = classes.get(codeDiv);
			if (classe == null) {
				report.addError("unknown.class.mapping");
				return;
			}
			groups = classe.getArray("groups");
		}
		if (groups != null) {
			for (Object o2: groups) {
				txXDT.add(STUDENTS_TO_GROUPS, new JsonObject()
						.putString("epj", epj)
						.putString("externalId", structureExternalId + "$" + o2.toString())
						.putString("source", UDT)
						.putNumber("inDate", importTimestamp)
						.putNumber("outDate", endStudents));
			}
		}
	}

	void addCoens(JsonObject currentEntity) {
		final String clf = currentEntity.getString("lignefic");
		Set<String> teachers = coens.get(clf);
		if (teachers == null) {
			teachers = new HashSet<>();
			coens.put(clf, teachers);
		}
		final String externalId = currentEntity.getString("epj");
		String teacherId = null;
		if (isNotEmpty(externalId)) {
			teacherId = teachersMapping.get(externalId);
		}
		if (isEmpty(teacherId)) {
			teacherId = this.teachers.get(currentEntity.getString("prof"));
		}
		if (isNotEmpty(teacherId)) {
			teachers.add(teacherId);
		}
	}

	void addFicheT(JsonObject currentEntity) {
		final String id = currentEntity.getString(CODE);
		fichesT.put(id, currentEntity);
	}

//	public void addCourse(JsonObject entity) {
//		try {
//			final String div = entity.getString("div");
//			if (isEmpty(div)) {
//				report.addError("invalid.class");
//				return;
//			}
//			final String fic = entity.getString("fic");
//			if (isEmpty(fic)) {
//				report.addError("invalid.fic");
//				return;
//			}
//			final Slot slot = slots.get(fic.substring(0, 3));
//			if (slot == null) {
//				report.addError("invalid.slot");
//				return;
//			}
//
//			JsonObject c = currentCourses.get(div);
//			if (c == null) {
//				final DateTime startDate = startDateWeek1.plusDays(Integer.parseInt(fic.substring(0, 3)) - 1);
//				c = new JsonObject()
//						.putString("structureId", structureId)
//						.putString("subjectId", subjects.get(entity.getString("mat")))
//						.putString("startDate", startDateWeek1.plusSeconds(slot.getStart()).toString())
////					.putString("endDate", startDate.plusWeeks(endCourseWeek - startCourseWeek)
////							.plusMinutes(placesNumber * slotDuration).toString())
//						.putNumber("dayOfWeek", startDate.getDayOfWeek())
//						.putArray("roomLabels", new JsonArray().add(rooms.get("salle")))
//						.putArray("teacherIds", new JsonArray().add(teachers.get("prof"))); // TODO add coens
//				c.putString("tmpId", JsonUtil.checksum(c));
//			} else {
//				//if
//			}
//		} catch (Exception e) {
//			report.addError(e.getMessage());
//		}
//	}

	void addCourse(JsonObject entity) {
		final String div = entity.getString("div");
		if (isEmpty(div)) {
		//	report.addError("invalid.class");
			return;
		}
		final String fic = entity.getString("fic");
		if (isEmpty(fic)) {
			report.addError("invalid.fic");
			return;
		}
		final Slot slot = slots.get(fic.substring(0, 3));
		if (slot == null) {
			report.addError("invalid.slot");
			return;
		}
		final String tmpId = calculateTmpId(entity);
		List<JsonObject> l = lfts.get(tmpId);
		if (l == null) {
			l = new ArrayList<>();
			lfts.put(tmpId, l);
		}
	}

	private String calculateTmpId(JsonObject entity) {
		return entity.getString("div") + entity.getString("mat") + entity.getString("prof") +
				entity.getString("rgpmt") + getOrElse(entity.getString("gpe"), "_", false);
	}

	private void generateCourses(int periodWeek) {
		for (List<JsonObject> c : lfts.values()) {
			Collections.sort(c, new LftComparator());
			String start = null;
			int current = 0;
			for (JsonObject j : c) {
				int val = Integer.parseInt(j.getString(CODE).substring(0, 3));
				if (start == null) {
					start = j.getString("fic");
					current = val;
				} else if ((++current) != val) {
					persistCourse(generateCourse(start, j.getString("fic"), j, periodWeek));
					start = null;
				}
			}
		}
	}

	private JsonObject generateCourse(String start, String end, JsonObject entity, int periodWeek) {
		JsonObject ficheTStart = fichesT.get(start);
		JsonObject ficheTEnd = fichesT.get(end);
		if (ficheTStart == null || ficheTEnd == null) {
			report.addError("invalid.ficheT");
			return null;
		}
		final Slot slotStart = slots.get(ficheTStart.getString("jour") +
				padLeft(ficheTStart.getString("demi_seq"), 2, '0') + ficheTStart.getString("site"));
		final Slot slotEnd = slots.get(ficheTEnd.getString("jour") +
				padLeft(ficheTEnd.getString("demi_seq"), 2, '0') + ficheTEnd.getString("site"));
		if (slotStart == null || slotEnd == null) {
			report.addError("invalid.slot");
			return null;
		}
		final int day = Integer.parseInt(ficheTStart.getString("jour"));
		final DateTime startDate = startDateWeek1.plusWeeks(periodWeek - 1).plusDays(day - 1).plusSeconds(slotStart.getStart());
		final DateTime endDate = startDateWeek1.plusWeeks(periods.get(periodWeek) - 1).plusDays(day - 1).plusSeconds(slotEnd.getEnd());
		final Set<String> ce = coens.get(start);
		JsonArray teacherIds;
		if (ce != null && ce.size() > 0) {
			teacherIds = new JsonArray(ce.toArray());
		} else {
			teacherIds = new JsonArray();
		}
		teacherIds.add(teachers.get("prof"));

		final JsonObject c = new JsonObject()
				.putString("structureId", structureId)
				.putString("subjectId", subjects.get(subjects.get(entity.getString("mat"))))
				.putString("startDate", startDate.toString())
				.putString("endDate", endDate.toString())
				.putNumber("dayOfWeek", day)
				.putArray("roomLabels", new JsonArray().add(rooms.get("salle")))
				.putArray("teacherIds", teacherIds)
				.putArray("classes", new JsonArray().add(classes.get(entity.getString("div")).getString("className")));
		JsonArray groups;
		if (isNotEmpty(entity.getString("rgpmt")) || isNotEmpty(entity.getString("gpe"))) {
			groups = new JsonArray();
			c.putArray("groups", groups);
			String name = regroup.get(entity.getString("rgpmt"));
			if (isNotEmpty(name)) {
				groups.add(name);
			}
			String gName = entity.getString("gpe");
			if (isNotEmpty(gName)) {
				groups.add(entity.getString("div") + " Gr " + gName);
			}
		}
		try {
			c.putString("_id", JsonUtil.checksum(c));
		} catch (NoSuchAlgorithmException e) {
			log.error("Error generating course checksum", e);
		}
		c.putNumber("modified", importTimestamp);
		return c;
	}

	private class LftComparator implements Comparator<JsonObject> {
		@Override
		public int compare(JsonObject o1, JsonObject o2) {
			return o1.getString(CODE).compareTo(o2.getString(CODE));
		}
	}

}
