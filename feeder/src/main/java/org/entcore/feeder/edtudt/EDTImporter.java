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

package org.entcore.feeder.edtudt;

import org.joda.time.DateTime;
import org.vertx.java.core.json.JsonArray;
import org.vertx.java.core.json.JsonObject;
import org.vertx.java.core.logging.Logger;
import org.vertx.java.core.logging.impl.LoggerFactory;
import org.xml.sax.InputSource;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.XMLReaderFactory;

import java.io.StringReader;
import java.util.*;

public class EDTImporter {

	private static final Logger log = LoggerFactory.getLogger(EDTImporter.class);
	public static final String IDENT = "Ident";
	private final List<String> ignoreAttributes = Arrays.asList("Etiquette", "Periode");
	private final EDTUtils edtUtils;
	private final String UAI;
	private final Map<String, String> rooms = new HashMap<>();
	private final Map<String, JsonObject> teachers = new HashMap<>();
	private final Map<String, JsonObject> students = new HashMap<>();
	private final Map<String, JsonObject> subjects = new HashMap<>();
	private final Map<String, JsonObject> classes = new HashMap<>();
	private final Map<String, JsonObject> groups = new HashMap<>();
	private HashMap<String, JsonObject> personnels = new HashMap<>();
	private DateTime startDateWeek1;
	private int slotDuration; // minutes

	public EDTImporter(EDTUtils edtUtils, String uai) {
		this.edtUtils = edtUtils;
		UAI = uai;
	}


	public void init() {

	}

	public void parse() throws Exception {
		//InputSource in = new InputSource("/home/dboissin/Docs/EDT - UDT/ImportCahierTexte/EDT/HarounTazieff.xml");
		InputSource in = new InputSource(new StringReader(edtUtils.decryptExport("/tmp/Edt_To_NEO-Open_1234567H.xml")));
		EDTHandler sh = new EDTHandler(this);
		XMLReader xr = XMLReaderFactory.createXMLReader();
		xr.setContentHandler(sh);
		xr.parse(in);
	}

	public void initSchoolYear(JsonObject schoolYear) {
		startDateWeek1 = DateTime.parse(schoolYear.getString("DatePremierJourSemaine1"));
	}

	public void initSchedule(JsonObject currentEntity) {
		slotDuration = Integer.parseInt(currentEntity.getString("DureePlace"));
		for (Object o : currentEntity.getArray("Place")) {
			if (!(o instanceof JsonObject) || !"0".equals(((JsonObject) o).getString("Numero"))) continue;
			String[] startHour = ((JsonObject) o).getString("LibelleHeureDebut").split(":");
			if (startHour.length == 3) {
				startDateWeek1 = startDateWeek1
						.plusHours(Integer.parseInt(startHour[0]))
						.plusMinutes(Integer.parseInt(startHour[1]))
						.plusSeconds(Integer.parseInt(startHour[2]));
				break;
			}
		}
	}

	public void addRoom(JsonObject currentEntity) {
		// TODO valid entity
		rooms.put(currentEntity.getString("Ident"), currentEntity.getString("Nom"));
	}

	public void addGroup(JsonObject currentEntity) {
		// TODO valid entity
		final String id = currentEntity.getString(IDENT);
		groups.put(id, currentEntity);
	}

	public void addClasse(JsonObject currentEntity) {
		// TODO valid entity
		final String id = currentEntity.getString(IDENT);
		classes.put(id, currentEntity);
	}

	public void addProfesseur(JsonObject currentEntity) {
		// TODO valid entity
		// TODO match Professeur
		final String id = currentEntity.getString(IDENT);
		teachers.put(id, currentEntity);
	}

	public void addEleve(JsonObject currentEntity) {
		// TODO valid entity
		final String id = currentEntity.getString(IDENT);
		students.put(id, currentEntity);
	}

	public void addSubject(JsonObject currentEntity) {
		// TODO valid entity
		final String id = currentEntity.getString(IDENT);
		subjects.put(id, currentEntity);
	}

	public void addPersonnel(JsonObject currentEntity) {
// TODO valid entity
		final String id = currentEntity.getString(IDENT);
		personnels.put(id, currentEntity);
	}

	public void addCourse(JsonObject currentEntity) {
		final List<Long> weeks = new ArrayList<>();
		final List<JsonObject> items = new ArrayList<>();
		final JsonArray courses = new JsonArray();

		for (String attr: currentEntity.getFieldNames()) {
			if (!ignoreAttributes.contains(attr) && currentEntity.getValue(attr) instanceof JsonArray) {
				for (Object o: currentEntity.getArray(attr)) {
					if (!(o instanceof JsonObject)) continue;
					final JsonObject j = (JsonObject) o;
					j.putString("itemType", attr);
					final String week = j.getString("Semaines");
					if (week != null) {
						weeks.add(Long.valueOf(week));
						items.add(j);
					}
				}
			}
		}

		if (currentEntity.containsField("SemainesAnnulation")) {
			log.info(currentEntity.encode());
		}
		final Long cancelWeek = (currentEntity.getString("SemainesAnnulation") != null) ?
				Long.valueOf(currentEntity.getString("SemainesAnnulation")) : null;
		BitSet lastWeek = new BitSet(weeks.size());
		int startCourseWeek = 0;
		for (int i = 1; i < 53; i++) {
			final BitSet currentWeek = new BitSet(weeks.size());
			boolean enabledCurrentWeek = false;
			for (int j = 0; j < weeks.size(); j++) {
				if (cancelWeek != null && ((1L << i) & cancelWeek) != 0) {
					currentWeek.set(j, false);
				} else {
					final Long week = weeks.get(j);
					currentWeek.set(j, ((1L << i) & week) != 0);
				}
				enabledCurrentWeek = enabledCurrentWeek | currentWeek.get(j);
			}
			if (!currentWeek.equals(lastWeek)) {
				if (startCourseWeek > 0) {
					courses.add(generateCourse(startCourseWeek, i - 1, lastWeek, items, currentEntity));
				}
				startCourseWeek = enabledCurrentWeek ? i : 0;
				lastWeek = currentWeek;
			}
		}
		if (cancelWeek != null) {
			log.info(courses.encode());
		}
	}

	private JsonObject generateCourse(int startCourseWeek, int endCourseWeek, BitSet enabledItems, List<JsonObject> items, JsonObject entity) {
		final int day = Integer.parseInt(entity.getString("Jour"));
		final int startPlace = Integer.parseInt(entity.getString("NumeroPlaceDebut"));
		final int placesNumber = Integer.parseInt(entity.getString("NombrePlaces"));
		final DateTime startDate = startDateWeek1.plusWeeks(startCourseWeek - 1)
				.plusDays(day - 1).plusMinutes(startPlace * slotDuration);
		final JsonObject c = new JsonObject()
				.putString("subjectCode", subjects.get(entity.getArray("Matiere").<JsonObject>get(0).getString("Ident")).getString("Code"))
				.putString("startDate", startDate.toString())
				.putString("endDate", startDate.plusWeeks(endCourseWeek - startCourseWeek)
						.plusMinutes(placesNumber * slotDuration).toString());

		for (int i = 0; i < enabledItems.size(); i++) {
			if (enabledItems.get(i)) {
				JsonObject item = items.get(i);
				switch (item.getString("itemType")) {
					case "Professeur":

						break;
					case "Classe":

						break;
					case "Groupe":

						break;
					case "PartieDeClasse":

						break;
					case "Materiel":

						break;
					case "Salle":

						break;
					case "Personnel":

						break;
				}
			}
		}
		return c;
	}


// {"Jour":"2","NumeroPlaceDebut":"4","NombrePlaces":"4","Annuel":"1","Matiere":[{"Ident":"195"}],"Professeur":[{"Ident":"54","Semaines":"4342484056092"}],"Classe":[{"Ident":"53","Semaines":"4342484056092"}],"Salle":[{"Ident":"56","Semaines":"4342484056092"}]}
//{"Jour":"2","NumeroPlaceDebut":"5","NombrePlaces":"3","Annuel":"1","Matiere":[{"Ident":"198"}],"Professeur":[{"Ident":"31","Semaines":"70317002848764"}],"Classe":[{"Ident":"22","Semaines":"70317002848764"}],"Salle":[{"Ident":"16","Semaines":"70317002848764"}]}
//	{"Jour":"3","NumeroPlaceDebut":"4","NombrePlaces":"2","Annuel":"1","Matiere":[{"Ident":"198"}],"Professeur":[{"Ident":"31","Semaines":"70317002848764"}],"Classe":[{"Ident":"22","Semaines":"70317002848764"}],"Salle":[{"Ident":"16","Semaines":"70317002848764"}]}
//	{"Jour":"1","NumeroPlaceDebut":"16","NombrePlaces":"4","Annuel":"1","Matiere":[{"Ident":"199"}],"Classe":[{"Ident":"32","Semaines":"67911820638716"}],"Salle":[{"Ident":"52","Semaines":"67911820638716"}]}

}
