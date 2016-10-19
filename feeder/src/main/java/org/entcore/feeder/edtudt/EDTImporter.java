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

import fr.wseduc.mongodb.MongoDb;
import fr.wseduc.webutils.DefaultAsyncResult;
import org.entcore.feeder.dictionary.users.PersEducNat;
import org.entcore.feeder.exceptions.TransactionException;
import org.entcore.feeder.exceptions.ValidationException;
import org.entcore.feeder.utils.JsonUtil;
import org.entcore.feeder.utils.Report;
import org.entcore.feeder.utils.TransactionHelper;
import org.entcore.feeder.utils.TransactionManager;
import org.joda.time.DateTime;
import org.vertx.java.core.AsyncResult;
import org.vertx.java.core.AsyncResultHandler;
import org.vertx.java.core.Handler;
import org.vertx.java.core.eventbus.Message;
import org.vertx.java.core.json.JsonArray;
import org.vertx.java.core.json.JsonObject;
import org.vertx.java.core.logging.Logger;
import org.vertx.java.core.logging.impl.LoggerFactory;
import org.xml.sax.InputSource;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.XMLReaderFactory;

import java.io.StringReader;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static fr.wseduc.webutils.Utils.getOrElse;
import static fr.wseduc.webutils.Utils.isEmpty;
import static fr.wseduc.webutils.Utils.isNotEmpty;
import static org.entcore.feeder.dictionary.structures.DefaultProfiles.PERSONNEL_PROFILE_EXTERNAL_ID;
import static org.entcore.feeder.dictionary.structures.DefaultProfiles.TEACHER_PROFILE_EXTERNAL_ID;

public class EDTImporter {

	private static final Logger log = LoggerFactory.getLogger(EDTImporter.class);
	private static final String MATCH_PERSEDUCNAT_QUERY =
			"MATCH (:Structure {UAI : {UAI}})<-[:DEPENDS]-(:ProfileGroup)<-[:IN]-(u:User) " +
			"WHERE head(u.profiles) = {profile} AND LOWER(u.lastName) = {lastName} AND LOWER(u.firstName) = {firstName} " +
			"SET u.IDPN = {IDPN} " +
			"RETURN DISTINCT u.id as id, u.IDPN as IDPN, head(u.profiles) as profile";
	private static final String UNKNOWN_CLASSES =
			"MATCH (s:Structure {UAI : {UAI}})<-[:BELONGS]-(c:Class) " +
			"WHERE c.name = {className} " +
			"WITH count(*) AS exists, s " +
			"WHERE exists = 0 " +
			"MERGE (cm:ClassesMapping { UAI : {UAI}}) " +
			"SET cm.unknownClasses = coalesce(FILTER(cn IN cm.unknownClasses WHERE cn <> {className}), []) + {className} " +
			"MERGE (s)<-[:MAPPING]-(cm) ";
	private static final String CREATE_GROUPS =
			"MATCH (s:Structure {externalId : {structureExternalId}}) " +
			"MERGE (fg:FunctionalGroup {externalId:{externalId}}) " +
			"ON CREATE SET fg.name = {name}, fg.id = {id} " +
			"MERGE (fg)-[:DEPENDS]->(s) ";
	private static final String CREATE_SUBJECT =
			"MATCH (s:Structure {externalId : {structureExternalId}}) " +
			"MERGE (sub:Subject {externalId : {externalId}}) " +
			"ON CREATE SET sub.code = {Code}, sub.label = {Libelle}, sub.id = {id} " +
			"MERGE (sub)-[:SUBJECT]->(s) ";
	public static final String IDENT = "Ident";
	public static final String IDPN = "IDPN";
	public static final String EDT = "EDT";
	public static final String COURSES = "courses";
	private final List<String> ignoreAttributes = Arrays.asList("Etiquette", "Periode", "PartieDeClasse");
	private final EDTUtils edtUtils;
	private final String UAI;
	private final Report report;
	private final JsonArray structure = new JsonArray();
	private String structureExternalId;
	private String structureId;
	private long importTimestamp;
	private final Map<String, String> teachersMapping = new HashMap<>();
	private final Map<String, JsonObject> notFoundPersEducNat = new HashMap<>();
//	private final Map<String, String> personnelsMapping = new HashMap<>();
	private final Map<String, String> equipments = new HashMap<>();
	private final Map<String, String> rooms = new HashMap<>();
	private final Map<String, String> teachers = new HashMap<>();
	private final Map<String, String> personnels = new HashMap<>();
	private final Map<String, String> students = new HashMap<>();
	private final Map<String, String> subjects = new HashMap<>();
	private final Map<String, JsonObject> classes = new HashMap<>();
	private final Map<String, JsonObject> subClasses = new HashMap<>();
	private final Map<String, JsonObject> groups = new HashMap<>();
	private JsonObject classesMapping;
	private final Map<String, String> subjectsMapping = new HashMap<>();
	private DateTime startDateWeek1;
	private int slotDuration; // minutes

	private PersEducNat persEducNat;
	private TransactionHelper txEdt;
	private final MongoDb mongoDb = MongoDb.getInstance();
	private final AtomicInteger countMongoQueries = new AtomicInteger(0);
	private AsyncResultHandler<Report> endHandler;


	public EDTImporter(EDTUtils edtUtils, String uai, String acceptLanguage) {
		this.edtUtils = edtUtils;
		UAI = uai;
		this.report = new Report(acceptLanguage);
	}

	public void init(final AsyncResultHandler<Void> handler) throws TransactionException {
		importTimestamp = System.currentTimeMillis();
		final String externalIdFromUAI = "MATCH (s:Structure {UAI : {UAI}}) return s.externalId as externalId, s.id as id";
		final String getUsersByProfile =
				"MATCH (:Structure {UAI : {UAI}})<-[:DEPENDS]-(:ProfileGroup)<-[:IN]-(u:User) " +
				"WHERE head(u.profiles) = {profile} AND NOT(u.IDPN IS NULL) AND NOT(u.externalId IS NULL) " +
				"RETURN DISTINCT u.id as id, u.IDPN as IDPN, head(u.profiles) as profile";
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
								teachersMapping.put(j.getString(IDPN), j.getString("id"));
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
						txEdt = TransactionManager.getTransaction();
						persEducNat = new PersEducNat(txEdt, report, EDT);
						persEducNat.setMapping("dictionary/mapping/edt/PersEducNat.json");
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

	public void launch(final AsyncResultHandler<Report> handler) throws Exception {
		final String content = edtUtils.decryptExport("/home/dboissin/Docs/EDT - UDT/Edt_To_NEO-Open_1234567H.xml");
		init(new AsyncResultHandler<Void>() {
			@Override
			public void handle(AsyncResult<Void> event) {
				if (event.succeeded()) {
					try {
						txEdt.setAutoSend(false);
						parse(content, true);
						if (txEdt.isEmpty()) {
							parse(content, false);
						} else {
							matchAndCreatePersEducNat(new AsyncResultHandler<Void>() {
								@Override
								public void handle(AsyncResult<Void> event) {
									if (event.succeeded()) {
										try {
											txEdt = TransactionManager.getTransaction();
											parse(content, false);
											txEdt.commit(new Handler<Message<JsonObject>>() {
												@Override
												public void handle(Message<JsonObject> event) {
													if (!"ok".equals(event.body().getString("status"))) {
														report.addError("error.commit.edt.transaction");
													}
													endHandler = handler;
													end();
												}
											});
										} catch (Exception e) {
											handler.handle(new DefaultAsyncResult<Report>(e));
										}
									} else {
										handler.handle(new DefaultAsyncResult<Report>(event.cause()));
									}
								}
							});
						}
					} catch (Exception e) {
						handler.handle(new DefaultAsyncResult<Report>(e));
					}
				} else {
					handler.handle(new DefaultAsyncResult<Report>(event.cause()));
				}
			}
		});
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
							endHandler.handle(new DefaultAsyncResult<Report>(report));
						}
					});
		}
	}

	public void parse(String content, boolean persEducNatOnly) throws Exception {
		//InputSource in = new InputSource("/home/dboissin/Docs/EDT - UDT/ImportCahierTexte/EDT/HarounTazieff.xml");
		InputSource in = new InputSource(new StringReader(content));
		EDTHandler sh = new EDTHandler(this, persEducNatOnly);
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
		rooms.put(currentEntity.getString(IDENT), currentEntity.getString("Nom"));
	}


	public void addEquipment(JsonObject currentEntity) {
		equipments.put(currentEntity.getString(IDENT), currentEntity.getString("Nom"));
	}

	public void addGroup(JsonObject currentEntity) {
		final String id = currentEntity.getString(IDENT);
		groups.put(id, currentEntity);
		final String name = currentEntity.getString("Nom");
		txEdt.add(CREATE_GROUPS, new JsonObject().putString("structureExternalId", structureExternalId).putString("name", name)
				.putString("externalId", structureExternalId + "$" + name).putString("id", UUID.randomUUID().toString()));
	}

	public void addClasse(JsonObject currentEntity) {
		final String id = currentEntity.getString(IDENT);
		classes.put(id, currentEntity);
		final JsonArray pcs = currentEntity.getArray("PartieDeClasse");
		final String ocn = currentEntity.getString("Nom");
		final String className = (classesMapping != null) ? getOrElse(classesMapping.getString(ocn), ocn, false) : ocn;
		currentEntity.putString("className", className);
		if (pcs != null) {
			for (Object o : pcs) {
				if (o instanceof JsonObject) {
					final String pcIdent = ((JsonObject) o).getString(IDENT);
					subClasses.put(pcIdent, ((JsonObject) o).putString("className", className));
				}
			}
		}
		txEdt.add(UNKNOWN_CLASSES, new JsonObject().putString("UAI", UAI).putString("className", className));
	}

	public void addProfesseur(JsonObject currentEntity) {
		// TODO manage users without IDPN
		final String id = currentEntity.getString(IDENT);
		final String idPronote = structureExternalId + "$" + currentEntity.getString(IDPN);
		final String teacherId = teachersMapping.get(idPronote);
		if (teacherId != null) {
			teachers.put(id, teacherId);
		} else {
			findPersEducNat(currentEntity, idPronote, "Teacher");
		}
	}

	public void addPersonnel(JsonObject currentEntity) {
		final String id = currentEntity.getString(IDENT);
		final String idPronote = structureExternalId + "$" + id; // fake pronote id
//		final String personnelId = personnelsMapping.get(idPronote);
//		if (personnelId != null) {
//			personnels.put(id, personnelId);
//		} else {
		findPersEducNat(currentEntity, idPronote, "Personnel");
//		}
	}

	private void findPersEducNat(JsonObject currentEntity, String idPronote, String profile) {
		log.info(currentEntity);
		try {
			JsonObject p = persEducNat.applyMapping(currentEntity);
			p.putArray("profiles", new JsonArray().add(profile));
			p.putString("externalId", idPronote);
			p.putString(IDPN, idPronote);
			notFoundPersEducNat.put(idPronote, p);
			txEdt.add(MATCH_PERSEDUCNAT_QUERY, new JsonObject().putString("UAI", UAI).putString(IDPN, idPronote)
					.putString("profile", profile)
					.putString("lastName", p.getString("lastName").toLowerCase())
					.putString("firstName", p.getString("firstName").toLowerCase()));
		} catch (Exception e) {
			report.addError(e.getMessage());
		}
	}

	private void matchAndCreatePersEducNat(final AsyncResultHandler<Void> handler) {
		txEdt.commit(new Handler<Message<JsonObject>>() {
			@Override
			public void handle(Message<JsonObject> event) {
				JsonArray res = event.body().getArray("results");
				if ("ok".equals(event.body().getString("status")) && res != null) {
					for (Object o : res) {
						setUsersId(o);
					}
					log.info("find : " + res.encodePrettily());
					if (!notFoundPersEducNat.isEmpty()) {
						try {
							TransactionHelper tx = TransactionManager.getTransaction();
							persEducNat.setTransactionHelper(tx);
							for (JsonObject p : notFoundPersEducNat.values()) {
								if ("Teacher".equals(p.getArray("profiles").<String>get(0))){
									persEducNat.createOrUpdatePersonnel(p, TEACHER_PROFILE_EXTERNAL_ID, structure, null, null, true, true);
								} else {
									persEducNat.createOrUpdatePersonnel(p, PERSONNEL_PROFILE_EXTERNAL_ID, structure, null, null, true, true);
								}
							}
							tx.commit(new Handler<Message<JsonObject>>() {
								@Override
								public void handle(Message<JsonObject> event) {
									JsonArray res = event.body().getArray("results");
									log.info("upsert : " + res.encodePrettily());
									if ("ok".equals(event.body().getString("status")) && res != null) {
										for (Object o : res) {
											setUsersId(o);
										}
										if (notFoundPersEducNat.isEmpty()) {
											handler.handle(new DefaultAsyncResult<>((Void) null));
										} else {
											handler.handle(new DefaultAsyncResult<Void>(new ValidationException("not.found.users.not.empty")));
										}
									} else {
										handler.handle(new DefaultAsyncResult<Void>(new TransactionException(event.body()
												.getString("message"))));
									}
								}
							});
						} catch (TransactionException e) {
							handler.handle(new DefaultAsyncResult<Void>(e));
						}
					} else {
						handler.handle(new DefaultAsyncResult<>((Void) null));
					}
				} else {
					handler.handle(new DefaultAsyncResult<Void>(new TransactionException(event.body().getString("message"))));
				}
			}

			private void setUsersId(Object o) {
				if ((o instanceof JsonArray) && ((JsonArray) o).size() > 0) {
					JsonObject j = ((JsonArray) o).get(0);
					String idPronote = j.getString(IDPN);
					String id = j.getString("id");
					String profile = j.getString("profile");
					if (isNotEmpty(id) && isNotEmpty(idPronote) && isNotEmpty(profile)) {
						notFoundPersEducNat.remove(idPronote);
						if ("Teacher".equals(profile)) {
							teachersMapping.put(idPronote, id);
						} else {
							String[] ident = idPronote.split("\\$");
							if (ident.length == 2) {
								personnels.put(ident[1], id);
							}
//							personnelsMapping.put(idPronote, id);
						}
					}
				}
			}
		});

	}

	public void addEleve(JsonObject currentEntity) {
		// TODO valid entity
		final String id = currentEntity.getString(IDENT);
		//students.put(id, currentEntity);
	}

	public void addSubject(JsonObject currentEntity) {
		final String id = currentEntity.getString(IDENT);
		String subjectId = subjectsMapping.get(currentEntity.getString("Code"));
		if (isEmpty(subjectId)) {
			final String externalId = structureExternalId + "$" + currentEntity.getString("Code");
			subjectId = UUID.randomUUID().toString();
			txEdt.add(CREATE_SUBJECT, currentEntity.putString("structureExternalId", structureExternalId)
					.putString("externalId", externalId).putString("id", subjectId));
		}
		subjects.put(id, subjectId);
	}

	public void addCourse(JsonObject currentEntity) {
		final List<Long> weeks = new ArrayList<>();
		final List<JsonObject> items = new ArrayList<>();

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
					persistCourse(generateCourse(startCourseWeek, i - 1, lastWeek, items, currentEntity));
				}
				startCourseWeek = enabledCurrentWeek ? i : 0;
				lastWeek = currentWeek;
			}
		}
	}

	private void persistCourse(JsonObject object) {
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

	private JsonObject generateCourse(int startCourseWeek, int endCourseWeek, BitSet enabledItems, List<JsonObject> items, JsonObject entity) {
		final int day = Integer.parseInt(entity.getString("Jour"));
		final int startPlace = Integer.parseInt(entity.getString("NumeroPlaceDebut"));
		final int placesNumber = Integer.parseInt(entity.getString("NombrePlaces"));
		final DateTime startDate = startDateWeek1.plusWeeks(startCourseWeek - 1)
				.plusDays(day - 1).plusMinutes(startPlace * slotDuration);
		final JsonObject c = new JsonObject()
				.putString("structureId", structureId)
				.putString("subjectId", subjects.get(entity.getArray("Matiere").<JsonObject>get(0).getString(IDENT)))
				.putString("startDate", startDate.toString())
				.putString("endDate", startDate.plusWeeks(endCourseWeek - startCourseWeek)
						.plusMinutes(placesNumber * slotDuration).toString())
				.putNumber("dayOfWeek", startDate.getDayOfWeek());

		for (int i = 0; i < enabledItems.size(); i++) {
			if (enabledItems.get(i)) {
				final JsonObject item = items.get(i);
				final String ident = item.getString(IDENT);
				switch (item.getString("itemType")) {
					case "Professeur":
						JsonArray teachersArray = c.getArray("teacherIds");
						if (teachersArray == null) {
							teachersArray = new JsonArray();
							c.putArray("teacherIds", teachersArray);
						}
						teachersArray.add(personnels.get(ident));
						break;
					case "Classe":
						JsonArray classesArray = c.getArray("classes");
						if (classesArray == null) {
							classesArray = new JsonArray();
							c.putArray("classes", classesArray);
						}
						classesArray.add(classes.get(ident).getString("className"));
						break;
					case "Groupe":
						JsonArray groupsArray = c.getArray("groups");
						if (groupsArray == null) {
							groupsArray = new JsonArray();
							c.putArray("groups", groupsArray);
						}
						groupsArray.add(groups.get(ident).getString("Nom"));
						break;
//					case "PartieDeClasse":
//
//						break;
					case "Materiel":
						JsonArray equipmentsArray = c.getArray("equipmentLabels");
						if (equipmentsArray == null) {
							equipmentsArray = new JsonArray();
							c.putArray("equipmentLabels", equipmentsArray);
						}
						equipmentsArray.add(equipments.get(ident));
						break;
					case "Salle":
						JsonArray roomsArray = c.getArray("roomLabels");
						if (roomsArray == null) {
							roomsArray = new JsonArray();
							c.putArray("roomLabels", roomsArray);
						}
						roomsArray.add(rooms.get(ident));
						break;
					case "Personnel":
						JsonArray personnelsArray = c.getArray("personnelIds");
						if (personnelsArray == null) {
							personnelsArray = new JsonArray();
							c.putArray("personnelIds", personnelsArray);
						}
						personnelsArray.add(personnels.get(ident));
						break;
				}
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


// {"Jour":"2","NumeroPlaceDebut":"4","NombrePlaces":"4","Annuel":"1","Matiere":[{"Ident":"195"}],"Professeur":[{"Ident":"54","Semaines":"4342484056092"}],"Classe":[{"Ident":"53","Semaines":"4342484056092"}],"Salle":[{"Ident":"56","Semaines":"4342484056092"}]}
//{"Jour":"2","NumeroPlaceDebut":"5","NombrePlaces":"3","Annuel":"1","Matiere":[{"Ident":"198"}],"Professeur":[{"Ident":"31","Semaines":"70317002848764"}],"Classe":[{"Ident":"22","Semaines":"70317002848764"}],"Salle":[{"Ident":"16","Semaines":"70317002848764"}]}
//	{"Jour":"3","NumeroPlaceDebut":"4","NombrePlaces":"2","Annuel":"1","Matiere":[{"Ident":"198"}],"Professeur":[{"Ident":"31","Semaines":"70317002848764"}],"Classe":[{"Ident":"22","Semaines":"70317002848764"}],"Salle":[{"Ident":"16","Semaines":"70317002848764"}]}
//	{"Jour":"1","NumeroPlaceDebut":"16","NombrePlaces":"4","Annuel":"1","Matiere":[{"Ident":"199"}],"Classe":[{"Ident":"32","Semaines":"67911820638716"}],"Salle":[{"Ident":"52","Semaines":"67911820638716"}]}

}
