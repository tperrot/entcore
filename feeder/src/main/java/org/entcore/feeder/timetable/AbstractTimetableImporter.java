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

import fr.wseduc.mongodb.MongoDb;
import fr.wseduc.webutils.DefaultAsyncResult;
import org.entcore.feeder.dictionary.structures.Transition;
import org.entcore.feeder.dictionary.users.PersEducNat;
import org.entcore.feeder.exceptions.TransactionException;
import org.entcore.feeder.exceptions.ValidationException;
import org.entcore.feeder.utils.Report;
import org.entcore.feeder.utils.TransactionHelper;
import org.entcore.feeder.utils.TransactionManager;
import org.joda.time.DateTime;
import org.vertx.java.core.AsyncResultHandler;
import org.vertx.java.core.Handler;
import org.vertx.java.core.eventbus.EventBus;
import org.vertx.java.core.eventbus.Message;
import org.vertx.java.core.json.JsonArray;
import org.vertx.java.core.json.JsonObject;
import org.vertx.java.core.logging.Logger;
import org.vertx.java.core.logging.impl.LoggerFactory;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static fr.wseduc.webutils.Utils.isEmpty;
import static fr.wseduc.webutils.Utils.isNotEmpty;

public abstract class AbstractTimetableImporter implements TimetableImporter {

	protected static final Logger log = LoggerFactory.getLogger(AbstractTimetableImporter.class);
	private static final String CREATE_SUBJECT =
			"MATCH (s:Structure {externalId : {structureExternalId}}) " +
			"MERGE (sub:Subject {externalId : {externalId}}) " +
			"ON CREATE SET sub.code = {Code}, sub.label = {Libelle}, sub.id = {id} " +
			"MERGE (sub)-[:SUBJECT]->(s) ";
	private static final String LINK_SUBJECT =
			"MATCH (s:Subject {id : {subjectId}}), (u:User) " +
			"WHERE u.id IN {teacherIds} " +
			"MERGE u-[r:TEACHES]->s " +
			"SET r.classes = FILTER(c IN coalesce(r.classes, []) where NOT(c IN r.classes)) + {classes}, " +
			"r.groups = FILTER(g IN coalesce(r.groups, []) where NOT(g IN r.groups)) + {groups} ";
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
	private static final String PERSEDUCNAT_TO_GROUPS =
			"MATCH (u:User {id : {id}}), (fg:FunctionalGroup) " +
					"WHERE fg.externalId IN {groups} " +
					"MERGE u-[:IN {source:{source}, outDate:{outDate}}]->fg ";
	public static final String COURSES = "courses";
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
	private final MongoDb mongoDb = MongoDb.getInstance();
	private final AtomicInteger countMongoQueries = new AtomicInteger(0);
	private AsyncResultHandler<Report> endHandler;

	protected AbstractTimetableImporter(String uai, String acceptLanguage) {
		UAI = uai;
		this.report = new Report(acceptLanguage);
	}

	protected void init(final AsyncResultHandler<Void> handler) throws TransactionException {
		importTimestamp = System.currentTimeMillis();
		final String externalIdFromUAI = "MATCH (s:Structure {UAI : {UAI}}) " +
				"return s.externalId as externalId, s.id as id, s.timetable as timetable ";
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
							if (!getSource().equals(a.<JsonObject>get(0).getString("timetable"))) {
								handler.handle(new DefaultAsyncResult<Void>(new TransactionException("different.timetable.type")));
								return;
							}
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

	protected void persistCourse(JsonObject object) {
		persEducNatToGroups(object);
		persEducNatToSubjects(object);
		// TODO add two phase commit
		countMongoQueries.incrementAndGet();
		JsonObject m = new JsonObject().putObject("$set", object)
				.putObject("$setOnInsert", new JsonObject().putNumber("created", importTimestamp));
		mongoDb.update(COURSES, new JsonObject().putString("_id", object.getString("_id")), m, true, false,
				new Handler<Message<JsonObject>>() {
					@Override
					public void handle(Message<JsonObject> event) {
						if (!"ok".equals(event.body().getString("status"))) {
							report.addError("error.persist.course");
						}
						if (countMongoQueries.decrementAndGet() == 0) {
							end();
						}
					}
				});
	}

	private void persEducNatToSubjects(JsonObject object) {
		final String subjectId = object.getString("subjectId");
		final JsonArray teacherIds = object.getArray("teacherIds");
		if (isNotEmpty(subjectId) && teacherIds != null && teacherIds.size() > 0) {
			final JsonObject params = new JsonObject()
					.putString("subjectId", subjectId)
					.putArray("teacherIds", teacherIds)
					.putArray("classes", object.getArray("classes", new JsonArray()))
					.putArray("groups", object.getArray("groups", new JsonArray()));
			txXDT.add(LINK_SUBJECT, params);
		}
	}

	private void persEducNatToGroups(JsonObject object) {
		final JsonArray groups = object.getArray("groups");
		if (groups != null) {
			final JsonArray teacherIds = object.getArray("teacherIds");
			final List<String> ids = new ArrayList<>();
			if (teacherIds != null) {
				ids.addAll(teacherIds.toList());
			}
			final JsonArray personnelIds = object.getArray("personnelIds");
			if (personnelIds != null) {
				ids.addAll(personnelIds.toList());
			}
			if (!ids.isEmpty()) {
				final JsonArray g = new JsonArray();
				for (Object o : groups) {
					g.add(structureExternalId + "$" + o.toString());
				}
				for (String id : ids) {
					txXDT.add(PERSEDUCNAT_TO_GROUPS, new JsonObject()
							.putArray("groups", g)
							.putString("id", id)
							.putString("source", getSource())
							.putNumber("outDate", DateTime.now().plusDays(1).getMillis()));
				}
			}
		}
	}

	private void end() {
		if (endHandler != null && countMongoQueries.get() == 0) {
			mongoDb.update(COURSES, new JsonObject().putString("structureId", structureId)
							.putObject("deleted", new JsonObject().putBoolean("$exists", false))
							.putObject("modified", new JsonObject().putNumber("$ne", importTimestamp)),
					new JsonObject().putObject("$set", new JsonObject().putNumber("deleted", importTimestamp)), false, true,
					new Handler<Message<JsonObject>>() {
						@Override
						public void handle(Message<JsonObject> event) {
							if (!"ok".equals(event.body().getString("status"))) {
								report.addError("error.set.deleted.courses");
							}
							endHandler.handle(new DefaultAsyncResult<>(report));
						}
					});
		}
	}

	protected void commit(final AsyncResultHandler<Report> handler) {
		txXDT.commit(new Handler<Message<JsonObject>>() {
			@Override
			public void handle(Message<JsonObject> event) {
				if (!"ok".equals(event.body().getString("status"))) {
					report.addError("error.commit.timetable.transaction");
				}
				endHandler = handler;
				end();
			}
		});
	}

	protected abstract String getSource();

	protected abstract String getTeacherMappingAttribute();

	public static void updateMergedUsers(JsonArray mergedUsers) {
		if (mergedUsers == null) return;
		long now = System.currentTimeMillis();
		for (Object o: mergedUsers) {
			if (o instanceof JsonObject) {
				final JsonObject j = (JsonObject) o;
				updateMergedUsers(j, now);
			} else if (o instanceof JsonArray) {
				final JsonArray a = (JsonArray) o;
				if (a.size() > 0) {
					updateMergedUsers(a.<JsonObject>get(0), now);
				}
			}
		}
	}

	private static void updateMergedUsers(JsonObject j, long now) {
		final String oldId = j.getString("oldId");
		final String id = j.getString("id");
		final String profile = j.getString("profile");
		if (isEmpty(oldId) || isEmpty(id) || (!"Teacher".equals(profile) && !"Personnel".equals(profile))) {
			return;
		}
		final JsonObject query = new JsonObject();
		final JsonObject modifier = new JsonObject();
		final String pl = profile.toLowerCase();
		query.putString(pl + "Ids", oldId);
		modifier.putObject("$set", new JsonObject()
				.putString(pl + "Ids.$", id)
				.putNumber("modified", now));
		MongoDb.getInstance().update(COURSES, query, modifier, false, true);
	}

	public static void initStructure(final EventBus eb, final Message<JsonObject> message) {
		final JsonObject conf = message.body().getObject("conf");
		if (conf == null) {
			message.reply(new JsonObject().putString("status", "error").putString("message", "invalid.conf"));
			return;
		}
		final String query =
				"MATCH (s:Structure {id:{structureId}}) " +
				"RETURN (NOT(HAS(s.timetable)) OR s.timetable <> {type}) as update ";
//				"WHERE NOT(HAS(s.timetable)) OR s.timetable <> {type} " +
//				"RETURN count(*) = 1 as update ";
		TransactionManager.getNeo4jHelper().execute(query, conf, new Handler<Message<JsonObject>>() {
			@Override
			public void handle(final Message<JsonObject> event) {
				final JsonArray j = event.body().getArray("result");
				if ("ok".equals(event.body().getString("status")) && j != null && j.size() == 1 &&
						j.<JsonObject>get(0).getBoolean("update", false)) {
					try {
						TransactionHelper tx = TransactionManager.getTransaction();
						final String q1 =
								"MATCH (s:Structure {id : {structureId}})<-[:DEPENDS]-(fg:FunctionalGroup) " +
								"WHERE NOT(HAS(s.timetable)) OR s.timetable <> {type} " +
								"OPTIONAL MATCH fg<-[:IN]-(u:User) " +
								"RETURN fg.id as group, fg.name as groupName, collect(u.id) as users ";
						final String q2 =
								"MATCH (s:Structure {id: {structureId}}) " +
								"WHERE NOT(HAS(s.timetable)) OR s.timetable <> {type} " +
								"SET s.timetable = {type} " +
								"WITH s " +
								"MATCH s<-[:DEPENDS]-(fg:FunctionalGroup), s<-[:SUBJECT]-(sub:Subject) " +
								"DETACH DELETE fg, sub ";
						tx.add(q1, conf);
						tx.add(q2, conf);
						tx.commit(new Handler<Message<JsonObject>>() {
							@Override
							public void handle(Message<JsonObject> res) {
								if ("ok".equals(res.body().getString("status"))) {
									final JsonArray r = res.body().getArray("results");
									if (r != null && r.size() == 2) {
										Transition.publishDeleteGroups(eb, log, r.<JsonArray>get(0));
									}
									message.reply(event.body());
								} else {
									message.reply(res.body());
								}
							}
						});
					} catch (TransactionException e) {
						log.error("Transaction error when init timetable structure", e);
						message.reply(new JsonObject().putString("status", "error").putString("message", e.getMessage()));
					}
				} else {
					message.reply(event.body());
				}
			}
		});
	}

}
