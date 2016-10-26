// Copyright © WebServices pour l'Éducation, 2014
//
// This file is part of ENT Core. ENT Core is a versatile ENT engine based on the JVM.
//
// This program is free software; you can redistribute it and/or modify
// it under the terms of the GNU Affero General Public License as
// published by the Free Software Foundation (version 3 of the License).
//
// For the sake of explanation, any module that communicate over native
// Web protocols, such as HTTP, with ENT Core is outside the scope of this
// license and could be license under its own terms. This is merely considered
// normal use of ENT Core, and does not fall under the heading of "covered work".
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.

import { http, Model, Collection, model } from 'entcore/entcore';
import { Document, quota } from 'entcore/workspace';

export class Folder extends Model{
	folders: Collection<Folder>;
	documents: Collection<Document>;
	_id: string;

	constructor(data){
		super(data);

		this.collection(Folder);
		this.collection(Document);
	}

	toTrashSelection(){
		this.documents.selection().forEach(function(document){
				http().put('/workspace/document/trash/' + document._id);
		});
		this.documents.all = this.documents.reject((doc) => doc.selected);

		this.folders.selection().forEach((folder) => {
			http().put('/workspace/folder/trash/' + folder._id).done($scope.reloadFolderView);
			this.folders.all = this.folders.reject((folder) => folder.selected);
		});
	}
}

export abstract class Tree extends Model{
	name: string;
	folders: Collection<Folder>

	abstract fill();
}

export class MyDocuments extends Tree{
	constructor(data){
		super(data);
		this.collection(Folder);
	}

	fill(){

	}
}

export class SharedDocuments extends Tree{
	constructor(data){
		super(data);
		this.collection(Folder);
	}

	fill(){

	}
}

export class AppDocuments extends Tree{
	constructor(data){
		super(data);
		this.collection(Folder);
	}
	
	fill(){

	}
}

export class Trash extends Tree{
	constructor(data){
		super(data);
		this.collection(Folder);
	}

	fill(){

	}
}

class Workspace extends Model{
	constructor(data?){
		super(data);
	}

	sync(){

	}
}

export let workspace = new Workspace();

model.build = function(){
	workspace.sync();
};
