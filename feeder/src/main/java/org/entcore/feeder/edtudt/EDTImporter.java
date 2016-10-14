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

import org.vertx.java.core.json.JsonObject;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.XMLReaderFactory;

import java.io.IOException;
import java.io.StringReader;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class EDTImporter {

	public static final String IDENT = "Ident";
	private final EDTUtils edtUtils;
	private final String UAI;
	private final Map<String, String> rooms = new HashMap<>();
	private final Map<String, JsonObject> teachers = new HashMap<>();
	private final Map<String, JsonObject> students = new HashMap<>();
	private final Map<String, JsonObject> subjects = new HashMap<>();
	private final Map<String, JsonObject> classes = new HashMap<>();
	private final Map<String, JsonObject> groups = new HashMap<>();
	private final Map<String, String> startPlaces = new HashMap<>(); // TODO use jodatime
	private final Map<String, String> endPlaces = new HashMap<>();

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

	public void addPlace(JsonObject currentEntity) {
		// TODO valid entity
		final String id = currentEntity.getString("Numero");
		startPlaces.put(id, currentEntity.getString("LibelleHeureDebut"));
		endPlaces.put(id, currentEntity.getString("LibelleHeureFin"));
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

	public void addCourse(JsonObject currentEntity) {
		final Set<String> semaines = new HashSet<>();
		for (Object o : currentEntity.getArray("Professeur")) {
			if (!(o instanceof JsonObject)) continue;
			final String s = ((JsonObject) o).getString("Semaines");
			if (!semaines.contains(s)) {
				semaines.add(s);
			}
		}
		for (Object o : currentEntity.getArray("Classe")) {
			if (!(o instanceof JsonObject)) continue;
			final String s = ((JsonObject) o).getString("Semaines");
			if (!semaines.contains(s)) {
				semaines.add(s);
			}
		}
		for (Object o : currentEntity.getArray("Salle")) {
			if (!(o instanceof JsonObject)) continue;
			final String s = ((JsonObject) o).getString("Semaines");
			if (!semaines.contains(s)) {
				semaines.add(s);
			}
		}

	}
// {"Jour":"2","NumeroPlaceDebut":"4","NombrePlaces":"4","Annuel":"1","Matiere":[{"Ident":"195"}],"Professeur":[{"Ident":"54","Semaines":"4342484056092"}],"Classe":[{"Ident":"53","Semaines":"4342484056092"}],"Salle":[{"Ident":"56","Semaines":"4342484056092"}]}
//{"Jour":"2","NumeroPlaceDebut":"5","NombrePlaces":"3","Annuel":"1","Matiere":[{"Ident":"198"}],"Professeur":[{"Ident":"31","Semaines":"70317002848764"}],"Classe":[{"Ident":"22","Semaines":"70317002848764"}],"Salle":[{"Ident":"16","Semaines":"70317002848764"}]}
//	{"Jour":"3","NumeroPlaceDebut":"4","NombrePlaces":"2","Annuel":"1","Matiere":[{"Ident":"198"}],"Professeur":[{"Ident":"31","Semaines":"70317002848764"}],"Classe":[{"Ident":"22","Semaines":"70317002848764"}],"Salle":[{"Ident":"16","Semaines":"70317002848764"}]}
//	{"Jour":"1","NumeroPlaceDebut":"16","NombrePlaces":"4","Annuel":"1","Matiere":[{"Ident":"199"}],"Classe":[{"Ident":"32","Semaines":"67911820638716"}],"Salle":[{"Ident":"52","Semaines":"67911820638716"}]}


}
