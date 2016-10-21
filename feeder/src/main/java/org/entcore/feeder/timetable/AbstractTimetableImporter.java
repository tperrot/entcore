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

package org.entcore.feeder.timetable;

import fr.wseduc.webutils.DefaultAsyncResult;
import org.entcore.feeder.dictionary.users.PersEducNat;
import org.entcore.feeder.exceptions.TransactionException;
import org.entcore.feeder.exceptions.ValidationException;
import org.entcore.feeder.utils.Report;
import org.entcore.feeder.utils.TransactionHelper;
import org.entcore.feeder.utils.TransactionManager;
import org.joda.time.DateTime;
import org.vertx.java.core.AsyncResultHandler;
import org.vertx.java.core.Handler;
import org.vertx.java.core.eventbus.Message;
import org.vertx.java.core.json.JsonArray;
import org.vertx.java.core.json.JsonObject;
import org.vertx.java.core.logging.Logger;
import org.vertx.java.core.logging.impl.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static fr.wseduc.webutils.Utils.isEmpty;

public abstract class AbstractTimetableImporter implements TimetableImporter {

	protected static final Logger log = LoggerFactory.getLogger(AbstractTimetableImporter.class);
	private static final String CREATE_SUBJECT =
			"MATCH (s:Structure {externalId : {structureExternalId}}) " +
			"MERGE (sub:Subject {externalId : {externalId}}) " +
			"ON CREATE SET sub.code = {Code}, sub.label = {Libelle}, sub.id = {id} " +
			"MERGE (sub)-[:SUBJECT]->(s) ";
	protected static final String UNKNOWN_CLASSES =
			"MATCH (s:Structure {UAI : {UAI}})<-[:BELONGS]-(c:Class) " +
			"WHERE c.name = {className} " +
			"WITH count(*) AS exists, s " +
			"WHERE exists = 0 " +
			"MERGE (cm:ClassesMapping { UAI : {UAI}}) " +
			"SET cm.unknownClasses = coalesce(FILTER(cn IN cm.unknownClasses WHERE cn <> {className}), []) + {className} " +
			"MERGE (s)<-[:MAPPING]-(cm) ";
	protected static final String CREATE_GROUPS =
			"MATCH (s:Structure {externalId : {structureExternalId}}) " +
			"MERGE (fg:FunctionalGroup {externalId:{externalId}}) " +
			"ON CREATE SET fg.name = {name}, fg.id = {id} " +
			"MERGE (fg)-[:DEPENDS]->(s) ";
	protected long importTimestamp;
	protected final String UAI;
	protected final Report report;
	protected final JsonArray structure = new JsonArray();
	protected String structureExternalId;
	protected String structureId;
	protected JsonObject classesMapping;
	protected DateTime startDateWeek1;
	protected int slotDuration; // seconds
	protected Map<String, Slot> slots = new HashMap<>();
	protected final Map<String, String> rooms = new HashMap<>();
	protected final Map<String, String> teachersMapping = new HashMap<>();
	protected final Map<String, String> teachers = new HashMap<>();
	protected final Map<String, String> subjectsMapping = new HashMap<>();
	protected final Map<String, String> subjects = new HashMap<>();
	protected final Map<String, JsonObject> classes = new HashMap<>();
	protected final Map<String, JsonObject> groups = new HashMap<>();

	protected PersEducNat persEducNat;
	protected TransactionHelper txXDT;

	protected AbstractTimetableImporter(String uai, String acceptLanguage) {
		UAI = uai;
		this.report = new Report(acceptLanguage);
	}

	protected void init(final AsyncResultHandler<Void> handler) throws TransactionException {
		importTimestamp = System.currentTimeMillis();
		final String externalIdFromUAI = "MATCH (s:Structure {UAI : {UAI}}) return s.externalId as externalId, s.id as id";
		final String tma = getTeacherMappingAttribute();
		final String getUsersByProfile =
				"MATCH (:Structure {UAI : {UAI}})<-[:DEPENDS]-(:ProfileGroup)<-[:IN]-(u:User) " +
				"WHERE head(u.profiles) = {profile} AND NOT(u." + tma +  " IS NULL) " +
				"RETURN DISTINCT u.id as id, u." + tma + " as tma, head(u.profiles) as profile";
		final String classesMappingQuery = "MATCH (s:Structure {UAI : {UAI}})<-[:MAPPING]-(cm:ClassesMapping) return cm";
		final String subjectsMappingQuery = "MATCH (s:Structure {UAI : {UAI}})<-[:SUBJECT]-(sub:Subject) return sub.code as code, sub.id as id";
		final TransactionHelper tx = TransactionManager.getTransaction();
		tx.add(getUsersByProfile, new JsonObject().putString("UAI", UAI).putString("profile", "Teacher"));
		//tx.add(getUsersByProfile, new JsonObject().putString("UAI", UAI).putString("profile", "Personnel"));
		tx.add(externalIdFromUAI, new JsonObject().putString("UAI", UAI));
		tx.add(classesMappingQuery, new JsonObject().putString("UAI", UAI));
		tx.add(subjectsMappingQuery, new JsonObject().putString("UAI", UAI));
		tx.commit(new Handler<Message<JsonObject>>() {
			@Override
			public void handle(Message<JsonObject> event) {
				final JsonArray res = event.body().getArray("results");
				if ("ok".equals(event.body().getString("status")) && res != null && res.size() == 4) {
					try {
						for (Object o : res.<JsonArray>get(0)) {
							if (o instanceof JsonObject) {
								final JsonObject j = (JsonObject) o;
								teachersMapping.put(j.getString("tma"), j.getString("id"));
							}
						}
//						for (Object o : res.<JsonArray>get(1)) {
//							if (o instanceof JsonObject) {
//								final JsonObject j = (JsonObject) o;
//								personnelsMapping.put(j.getString(IDPN), j.getString("id"));
//							}
//						}
//						JsonArray a = res.get(2);
						JsonArray a = res.get(1);
						if (a != null && a.size() == 1) {
							structureExternalId = a.<JsonObject>get(0).getString("externalId");
							structure.add(structureExternalId);
							structureId = a.<JsonObject>get(0).getString("id");
						} else {
							handler.handle(new DefaultAsyncResult<Void>(new ValidationException("invalid.uai")));
							return;
						}
						JsonArray cm = res.get(2);
						if (cm != null && cm.size() == 1) {
							classesMapping = cm.get(0);
						}
						JsonArray subjects = res.get(3);
						if (subjects != null && subjects.size() > 0) {
							for (Object o : subjects) {
								if (o instanceof JsonObject) {
									final  JsonObject s = (JsonObject) o;
									subjectsMapping.put(s.getString("code"), s.getString("id"));
								}
							}
						}
						txXDT = TransactionManager.getTransaction();
						persEducNat = new PersEducNat(txXDT, report, getSource());
						persEducNat.setMapping("dictionary/mapping/" + getSource().toLowerCase() + "/PersEducNat.json");
						handler.handle(new DefaultAsyncResult<>((Void) null));
					} catch (Exception e) {
						handler.handle(new DefaultAsyncResult<Void>(e));
					}
				} else {
					handler.handle(new DefaultAsyncResult<Void>(new TransactionException(event.body().getString("message"))));
				}
			}
		});
	}

	protected void addSubject(String id, JsonObject currentEntity) {
		String subjectId = subjectsMapping.get(currentEntity.getString("Code"));
		if (isEmpty(subjectId)) {
			final String externalId = structureExternalId + "$" + currentEntity.getString("Code");
			subjectId = UUID.randomUUID().toString();
			txXDT.add(CREATE_SUBJECT, currentEntity.putString("structureExternalId", structureExternalId)
					.putString("externalId", externalId).putString("id", subjectId));
		}
		subjects.put(id, subjectId);
	}

	protected abstract String getSource();

	protected abstract String getTeacherMappingAttribute();

}
