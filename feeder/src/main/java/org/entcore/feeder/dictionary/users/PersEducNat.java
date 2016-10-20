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

package org.entcore.feeder.dictionary.users;

import org.entcore.feeder.timetable.edt.EDTImporter;
import org.entcore.feeder.utils.Neo4j;
import org.entcore.feeder.utils.Report;
import org.entcore.feeder.utils.TransactionHelper;
import org.entcore.feeder.utils.Validator;
import org.vertx.java.core.json.JsonArray;
import org.vertx.java.core.json.JsonObject;

import java.util.Map;
import java.util.Set;

public class PersEducNat extends AbstractUser {

	private final Validator personnelValidator = new Validator("dictionary/schema/Personnel.json");

	public PersEducNat(TransactionHelper transactionHelper, Report report, String currentSource) {
		super(transactionHelper, report, currentSource);
	}

	public PersEducNat(TransactionHelper transactionHelper, Map<String, String> externalIdMapping, Set<String> userImportedExternalId, Report report, String currentSource) {
		super(transactionHelper, externalIdMapping, userImportedExternalId, report, currentSource);
	}

	public void createOrUpdatePersonnel(JsonObject object, String profileExternalId, JsonArray structuresByFunctions,
				String[][] linkClasses, String[][] linkGroups, boolean nodeQueries, boolean relationshipQueries) {
		final String error = personnelValidator.validate(object);
		if (error != null) {
			if (object.getArray("profiles") != null && object.getArray("profiles").size() == 1) {
				report.addIgnored(object.getArray("profiles").<String>get(0), error, object);
			} else {
				report.addIgnored("Personnel", error, object);
			}
			log.warn(error);
		} else {
			if (nodeQueries) {
				object.putString("source", currentSource);
				if (userImportedExternalId != null) {
					userImportedExternalId.add(object.getString("externalId"));
				}
				StringBuilder sb = new StringBuilder();
				JsonObject params;
				sb.append("MERGE (u:`User` { externalId : {externalId}}) ");
				sb.append("ON CREATE SET u.id = {id}, u.login = {login}, u.activationCode = {activationCode}, ");
				sb.append("u.displayName = {displayName} ");
				sb.append("WITH u ");
				sb.append("WHERE u.checksum IS NULL OR u.checksum <> {checksum} ");
				sb.append("SET ").append(Neo4j.nodeSetPropertiesFromJson("u", object,
						"id", "externalId", "login", "activationCode", "displayName", "email"));
				if (EDTImporter.EDT.equals(currentSource)) {
					sb.append("RETURN u.id as id, u.IDPN as IDPN, head(u.profiles) as profile");
				}
				params = object;
				transactionHelper.add(sb.toString(), params);
				checkUpdateEmail(object);
			}
			if (relationshipQueries) {
				final String externalId = object.getString("externalId");
				JsonArray structures = getMappingStructures(object.getArray("structures"));
				if (externalId != null && structures != null && structures.size() > 0) {
					String query;
					JsonObject p = new JsonObject().putString("userExternalId", externalId);
					if (structures.size() == 1) {
						query = "MATCH (s:Structure), (u:User) " +
								"USING INDEX s:Structure(externalId) " +
								"USING INDEX u:User(externalId) " +
								"WHERE s.externalId = {structureAdmin} AND u.externalId = {userExternalId} " +
								"MERGE u-[:ADMINISTRATIVE_ATTACHMENT]->s ";
						p.putString("structureAdmin", (String) structures.get(0));
					} else {
						query = "MATCH (s:Structure), (u:User) " +
								"USING INDEX s:Structure(externalId) " +
								"USING INDEX u:User(externalId) " +
								"WHERE s.externalId IN {structuresAdmin} AND u.externalId = {userExternalId} " +
								"MERGE u-[:ADMINISTRATIVE_ATTACHMENT]->s ";
						p.putArray("structuresAdmin", structures);
					}
					transactionHelper.add(query, p);
				}
				if (externalId != null && structuresByFunctions != null && structuresByFunctions.size() > 0) {
					String query;
					structuresByFunctions = getMappingStructures(structuresByFunctions);
					JsonObject p = new JsonObject().putString("userExternalId", externalId);
					if (structuresByFunctions.size() == 1) {
						query = "MATCH (s:Structure)<-[:DEPENDS]-(g:ProfileGroup)-[:HAS_PROFILE]->(p:Profile), " +
								"(:User { externalId : {userExternalId}})-[:MERGED*0..1]->(u:User) " +
								"USING INDEX s:Structure(externalId) " +
								"USING INDEX p:Profile(externalId) " +
								"WHERE s.externalId = {structureAdmin} AND NOT(HAS(u.mergedWith)) " +
								"AND p.externalId = {profileExternalId} " +
								"MERGE u-[:IN]->g";
						p.putString("structureAdmin", (String) structuresByFunctions.get(0))
								.putString("profileExternalId", profileExternalId);
					} else {
						query = "MATCH (s:Structure)<-[:DEPENDS]-(g:ProfileGroup)-[:HAS_PROFILE]->(p:Profile), " +
								"(:User { externalId : {userExternalId}})-[:MERGED*0..1]->(u:User) " +
								"USING INDEX s:Structure(externalId) " +
								"USING INDEX p:Profile(externalId) " +
								"WHERE s.externalId IN {structuresAdmin} AND NOT(HAS(u.mergedWith)) " +
								"AND p.externalId = {profileExternalId} " +
								"MERGE u-[:IN]->g ";
						p.putArray("structuresAdmin", structuresByFunctions)
								.putString("profileExternalId", profileExternalId);
					}
					transactionHelper.add(query, p);
					String qs =
							"MATCH (:User {externalId : {userExternalId}})-[r:IN|COMMUNIQUE]-(:Group)-[:DEPENDS]->(s:Structure) " +
									"WHERE NOT(s.externalId IN {structures}) AND (NOT(HAS(r.source)) OR r.source = {source}) " +
									"DELETE r";
					JsonObject ps = new JsonObject()
							.putString("userExternalId", externalId)
							.putString("source", currentSource)
							.putArray("structures", structuresByFunctions);
					transactionHelper.add(qs, ps);
				}
				if (externalId != null && linkClasses != null) {
					JsonArray classes = new JsonArray();

					for (String[] structClass : linkClasses) {
						if (structClass != null && structClass[0] != null && structClass[1] != null) {
							String query =
									"MATCH (s:Structure)<-[:BELONGS]-(c:Class)<-[:DEPENDS]-(g:ProfileGroup)" +
											"-[:DEPENDS]->(pg:ProfileGroup)-[:HAS_PROFILE]->(p:Profile), " +
											"(:User { externalId : {userExternalId}})-[:MERGED*0..1]->(u:User) " +
											"USING INDEX s:Structure(externalId) " +
											"USING INDEX p:Profile(externalId) " +
											"WHERE s.externalId = {structure} AND c.externalId = {class} " +
											"AND NOT(HAS(u.mergedWith)) AND p.externalId = {profileExternalId} " +
											"MERGE u-[:IN]->g";
							JsonObject p = new JsonObject()
									.putString("userExternalId", externalId)
									.putString("profileExternalId", profileExternalId)
									.putString("structure", structClass[0])
									.putString("class", structClass[1]);
							transactionHelper.add(query, p);
							classes.add(structClass[1]);
						}
					}
					String q =
							"MATCH (:User {externalId : {userExternalId}})-[r:IN|COMMUNIQUE]-(:Group)-[:DEPENDS]->(c:Class) " +
									"WHERE NOT(c.externalId IN {classes}) AND (NOT(HAS(r.source)) OR r.source = {source}) " +
									"DELETE r";
					JsonObject p = new JsonObject()
							.putString("userExternalId", externalId)
							.putString("source", currentSource)
							.putArray("classes", classes);
					transactionHelper.add(q, p);
				}
				final JsonArray groups = new JsonArray();
				if (externalId != null && linkGroups != null) {
					for (String[] structGroup : linkGroups) {
						if (structGroup != null && structGroup[0] != null && structGroup[1] != null) {
							String query =
									"MATCH (s:Structure)" +
											"<-[:DEPENDS]-(g:FunctionalGroup), " +
											"(u:User) " +
											"USING INDEX s:Structure(externalId) " +
											"USING INDEX u:User(externalId) " +
											"WHERE s.externalId = {structure} AND g.externalId = {group} AND u.externalId = {userExternalId} " +
											"MERGE u-[:IN]->g";
							JsonObject p = new JsonObject()
									.putString("userExternalId", externalId)
									.putString("structure", structGroup[0])
									.putString("group", structGroup[1]);
							transactionHelper.add(query, p);
							groups.add(structGroup[1]);
						}
					}
				}
				if (externalId != null) {
					final String qdfg =
							"MATCH (:User {externalId : {userExternalId}})-[r:IN|COMMUNIQUE]-(g:FunctionalGroup) " +
									"WHERE NOT(g.externalId IN {groups}) AND (NOT(HAS(r.source)) OR r.source = {source}) " +
									"DELETE r";
					final JsonObject pdfg = new JsonObject()
							.putString("userExternalId", externalId)
							.putString("source", currentSource)
							.putArray("groups", groups);
					transactionHelper.add(qdfg, pdfg);
				}
			}
		}
	}

}
