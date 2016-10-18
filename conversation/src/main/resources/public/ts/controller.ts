import { ng, notify, idiom as lang, template, skin, workspace } from 'entcore/entcore';
import { Mail, User, UserFolder, Launcher, quota, conversation, Trash, SystemFolder } from './model/conversation';

import { $ } from 'entcore/libs/jquery/jquery';
import { _ } from 'entcore/libs/underscore/underscore';

export let conversationController = ng.controller('ConversationController', [
    '$scope', '$timeout', 'model', 'route', function ($scope, $timeout, model, route) {
        $scope.viewsContainers = {};
        $scope.selection = {
            selectAll: false
        };
        $scope.conversation = conversation;

        $scope.openView = function (view: string, name: string) {
            $scope.users.found = [];
            $scope.users.search = '';

            $scope.newItem = new Mail();
            $scope.selection.selectAll = false;
            var viewsPath = '/conversation/public/template/';
            $scope.viewsContainers[name] = viewsPath + view + '.html'
        }
    
        route({
            readMail: function (params) {
                conversation.folders.openFolder('inbox');
                template.open('page', 'folders');
                $scope.readMail(new Mail({ id: params.mailId }));
            },
            writeMail: function (params) {
                conversation.folders.openFolder('inbox');
                conversation.users.on('sync', function () {
                    if (this.findWhere({ id: params.userId })) {
                        template.open('page', 'folders');
                        new User({ id: params.userId }).findData(function () {
                            $scope.openView('write-mail', 'main');
                            $scope.addUser(this);
                        });
                    }
                    else {
                        template.open('page', 'e401')
                    }
                });
            },
            inbox: function () {
                conversation.folders.openFolder('inbox');
                template.open('page', 'folders');
            }
        });

        $scope.lang = lang;
        $scope.notify = notify;
        $scope.folders = conversation.folders;
        $scope.userFolders = conversation.userFolders;
        $scope.users = { list: conversation.users, search: '', found: [], foundCC: [] };
        $scope.maxDepth = conversation.maxFolderDepth;
        $scope.newItem = new Mail();
        $scope.openView('inbox', 'main');
        $scope.formatFileType = workspace.Document.role;

        $scope.clearSearch = function () {
            $scope.users.found = [];
            $scope.users.search = '';
        };

        $scope.clearCCSearch = function () {
            $scope.users.foundCC = [];
            $scope.users.searchCC = '';
        };

        $scope.resetScope = function () {
            $scope.openInbox();
        };

        $scope.containsView = function (name, view) {
            var viewsPath = '/conversation/public/template/';
            return $scope.viewsContainers[name] === viewsPath + view + '.html';
        };

        $scope.openFolder = function (folderName) {
            if (!folderName) {
                if (conversation.currentFolder instanceof UserFolder) {
                    $scope.openUserFolder(conversation.currentFolder, {});
                    return;
                }
                folderName = (conversation.currentFolder as SystemFolder).folderName;
            }
            $scope.mail = undefined;
            conversation.folders.openFolder(folderName);
            $scope.openView(folderName, 'main');
        };

        $scope.openUserFolder = function (folder, obj) {
            $scope.mail = undefined
            conversation.currentFolder = folder
            folder.mails.full = false
            folder.pageNumber = 0
            obj.template = ''
            folder.userFolders.sync(function () {
                $timeout(function () {
                    obj.template = 'folder-content'
                }, 10)
            })
            folder.mails.sync()
            $scope.openView('folder', 'main');

            $timeout(function () {
                $('body').trigger('whereami.update');
            }, 100)
        };

        $scope.isParentOf = function (folder, targetFolder) {
            if (!targetFolder || !targetFolder.parentFolder)
                return false

            var ancestor = targetFolder.parentFolder
            while (ancestor) {
                if (folder.id === ancestor.id)
                    return true
                ancestor = ancestor.parentFolder
            }
            return false
        }

        $scope.getSystemFolder = function (mail) {
            if (mail.from !== model.me.userId && mail.state === "SENT")
                return 'INBOX'
            if (mail.from === model.me.userId && mail.state === "SENT")
                return 'OUTBOX'
            if (mail.from === model.me.userId && mail.state === "DRAFT")
                return 'DRAFT'
            return ''
        }

        $scope.matchSystemIcon = function (mail) {
            var systemFolder = $scope.getSystemFolder(mail)
            if (systemFolder === "INBOX")
                return 'mail-in'
            if (systemFolder === "OUTBOX")
                return 'mail-out'
            if (systemFolder === "DRAFT")
                return 'mail-new'
            return ''
        }

        $scope.variableMailAction = function (mail) {
            var systemFolder = $scope.getSystemFolder(mail)
            if (systemFolder === "DRAFT")
                return $scope.editDraft(mail)
            else if (systemFolder === "OUTBOX")
                return $scope.viewMail(mail)
            else
                return $scope.readMail(mail)
        }

        $scope.removeFromUserFolder = function (event, mail) {
            conversation.currentFolder.mails.selection().forEach(function (mail) {
                mail.removeFromFolder();
            });
            conversation.currentFolder.mails.removeSelection();
        };

        $scope.nextPage = function () {
            conversation.currentFolder.nextPage();
        };

        $scope.switchSelectAll = function () {
            if ($scope.selection.selectAll) {
                conversation.currentFolder.mails.selectAll();
                if (conversation.currentFolder.userFolders)
                    conversation.currentFolder.userFolders.selectAll()
            }
            else {
                conversation.currentFolder.mails.deselectAll();
                if (conversation.currentFolder.userFolders)
                    conversation.currentFolder.userFolders.deselectAll()
            }
        };

        function setCurrentMail(mail, doNotSelect?: boolean) {
            conversation.currentFolder.mails.current = mail;
            conversation.currentFolder.mails.deselectAll();
            if (!doNotSelect)
                conversation.currentFolder.mails.current.selected = true;
            $scope.mail = mail;
        }

        $scope.viewMail = function (mail) {
            $scope.openView('view-mail', 'main');
            setCurrentMail(mail);
            mail.open();
        };

        $scope.refresh = function () {
            notify.info('updating');
            conversation.currentFolder.mails.refresh();
            conversation.folders.inbox.countUnread();
        };

        $scope.readMail = function (mail) {
            $scope.openView('read-mail', 'main');
            setCurrentMail(mail, true);
            mail.open(function () {
                if (!mail.state) {
                    template.open('page', 'e404');
                }
                $scope.$root.$emit('refreshMails');
            });
        };

        $scope.transfer = function () {
            $scope.openView('write-mail', 'main');
            $scope.newItem.parentConversation = $scope.mail;
            $scope.newItem.setMailContent($scope.mail, 'transfer');
            conversation.folders.draft.transfer($scope.newItem);
        };

        $scope.reply = function () {
            $scope.openView('write-mail', 'main');
            $scope.newItem.parentConversation = $scope.mail;
            $scope.newItem.setMailContent($scope.mail, 'reply');
            $scope.addUser($scope.mail.sender());
        };

        $scope.replyAll = function () {
            $scope.openView('write-mail', 'main');
            $scope.newItem.parentConversation = $scope.mail;
            $scope.newItem.setMailContent($scope.mail,'reply', true);
            $scope.newItem.to = _.filter($scope.newItem.to, function (user) { return user.id !== model.me.userId })
            $scope.newItem.cc = _.filter($scope.newItem.cc, function (user) {
                return user.id !== model.me.userId && !_.findWhere($scope.newItem.to, { id: user.id })
            })
            if (!_.findWhere($scope.newItem.to, { id: $scope.mail.sender().id }))
                $scope.addUser($scope.mail.sender());
        };

        $scope.editDraft = function (draft) {
            $scope.openView('write-mail', 'main');
            draft.open();
            $scope.newItem = draft;
        };

        $scope.saveDraft = function () {
            notify.info('draft.saved');

            conversation.folders.draft.saveDraft($scope.newItem);
            $scope.openFolder(conversation.folders.draft.folderName);
        };

        $scope.sendMail = function () {
            $scope.newItem.send();
            $scope.openFolder(conversation.folders.outbox.folderName);
        };

        $scope.restore = function () {
            if (conversation.folders.trash.mails.selection().length > 0)
                conversation.folders.trash.restoreMails();
            if (conversation.folders.trash.userFolders) {
                var launcher = new Launcher(conversation.folders.trash.userFolders.selection().length, function () {
                    conversation.folders.trash.userFolders.sync()
                    $scope.refreshFolders()
                })
                _.forEach(conversation.folders.trash.userFolders.selection(), function (userFolder) {
                    userFolder.restore().done(function () {
                        launcher.launch()
                    })
                })
            }
        };

        $scope.removeSelection = function () {
            if (conversation.currentFolder.mails.selection().length > 0) {
                conversation.currentFolder.mails.removeMails();
            }
            if (conversation.currentFolder.userFolders) {
                var launcher = new Launcher(conversation.currentFolder.userFolders.selection().length, function () {
                    conversation.currentFolder.sync()
                    $scope.refreshFolders()
                    $scope.getQuota()
                })
                _.forEach(conversation.currentFolder.userFolders.selection(), function (userFolder) {
                    userFolder.delete().done(function () {
                        launcher.launch()
                    })
                })
            }
        };

        $scope.allReceivers = function (mail) {
            var receivers = mail.to.slice(0);
            mail.toName && mail.toName.forEach(function (deletedReceiver) {
                receivers.push({
                    deleted: true,
                    displayName: deletedReceiver
                });
            });
            return receivers;
        }

        $scope.filterUsers = function (mail) {
            return function (user) {
                if (user.deleted) {
                    return true
                }
                var mapped = mail.map(user)
                return typeof mapped !== 'undefined' && typeof mapped.displayName !== 'undefined' && mapped.displayName.length > 0
            }
        }

        $scope.updateFoundCCUsers = function () {
            var include = [];
            var exclude = $scope.newItem.cc || [];
            if ($scope.mail) {
                include = _.map($scope.mail.displayNames, function (item) {
                    return new User({ id: item[0], displayName: item[1] });
                });
            }
            $scope.users.foundCC = conversation.users.findUser($scope.users.searchCC, include, exclude);
        };

        $scope.updateFoundUsers = function () {
            var include = [];
            var exclude = $scope.newItem.to || [];
            if ($scope.mail) {
                include = _.map($scope.mail.displayNames, function (item) {
                    return new User({ id: item[0], displayName: item[1] });
                });
            }
            $scope.users.found = conversation.users.findUser($scope.users.search, include, exclude);
        };

        $scope.addUser = function (user) {
            if (!$scope.newItem.to) {
                $scope.newItem.to = [];
            }
            if (user) {
                $scope.newItem.currentReceiver = user;
            }
            $scope.newItem.to.push($scope.newItem.currentReceiver);
        };

        $scope.removeUser = function (user) {
            $scope.newItem.to = _.reject($scope.newItem.to, function (item) { return item === user; });
        };

        $scope.addCCUser = function (user) {
            if (!$scope.newItem.cc) {
                $scope.newItem.cc = [];
            }
            if (user) {
                $scope.newItem.currentCCReceiver = user;
            }
            $scope.newItem.cc.push($scope.newItem.currentCCReceiver);
        };

        $scope.removeCCUser = function (user) {
            $scope.newItem.cc = _.reject($scope.newItem.cc, function (item) { return item === user; });
        };

        $scope.template = template
        $scope.lightbox = {}

        $scope.rootFolderTemplate = { template: 'folder-root-template' }
        $scope.refreshFolders = function () {
            $scope.userFolders.sync(function () {
                $scope.rootFolderTemplate.template = ""
                $timeout(function () {
                    $scope.$apply()
                    $scope.rootFolderTemplate.template = 'folder-root-template'
                }, 100)
            })
        }

        $scope.currentFolderDepth = function () {
            if (!($scope.currentFolder instanceof UserFolder))
                return 0

            return $scope.currentFolder.depth();
        }

        $scope.moveSelection = function () {
            $scope.destination = {}
            $scope.lightbox.show = true
            template.open('lightbox', 'move-mail')
        }

        $scope.moveToFolderClick = function (folder, obj) {
            obj.template = ''

            if (folder.userFolders.length() > 0) {
                $timeout(function () {
                    obj.template = 'move-folders-content'
                }, 10)
                return
            }

            folder.userFolders.sync(function () {
                $timeout(function () {
                    obj.template = 'move-folders-content'
                }, 10)
            })
        }

        $scope.moveMessages = function (folderTarget) {
            $scope.lightbox.show = false
            template.close('lightbox')
            conversation.currentFolder.mails.moveSelection(folderTarget)
        }

        $scope.openNewFolderView = function () {
            $scope.newFolder = new UserFolder();
            if (conversation.currentFolder instanceof UserFolder) {
                $scope.newFolder.parentFolderId = (conversation.currentFolder as UserFolder).id;
            }
            
            $scope.lightbox.show = true
            template.open('lightbox', 'create-folder')
        }
        $scope.createFolder = function () {
            $scope.newFolder.create().done(function () {
                $scope.refreshFolders()
            })
            $scope.lightbox.show = false
            template.close('lightbox')
        }
        $scope.openRenameFolderView = function (folder) {
            $scope.targetFolder = new UserFolder()
            $scope.targetFolder.name = folder.name
            $scope.targetFolder.id = folder.id
            $scope.lightbox.show = true
            template.open('lightbox', 'update-folder')
        }
        $scope.updateFolder = function () {
            var current = $scope.currentFolder
            $scope.targetFolder.update().done(function () {
                current.name = $scope.targetFolder.name
                $scope.$apply()
            })
            $scope.lightbox.show = false
            template.close('lightbox')
        }
        $scope.trashFolder = function (folder) {
            folder.trash().done(function () {
                $scope.refreshFolders()
                $scope.openFolder('trash')
            })
        }
        $scope.restoreFolder = function (folder) {
            folder.restore().done(function () {
                $scope.refreshFolders()
            })
        }
        $scope.deleteFolder = function (folder) {
            folder.delete().done(function () {
                $scope.refreshFolders()
            })
        }

        var letterIcon = document.createElement("img")
        letterIcon.src = skin.theme + ".." + "/img/icons/message-icon.png"
        $scope.drag = function (item, $originalEvent) {
            $originalEvent.dataTransfer.setDragImage(letterIcon, 0, 0);
            try {
                $originalEvent.dataTransfer.setData('application/json', JSON.stringify(item));
            } catch (e) {
                $originalEvent.dataTransfer.setData('Text', JSON.stringify(item));
            }
        };
        $scope.dropCondition = function (targetItem) {
            return function (event) {
                let dataField = event.dataTransfer.types.indexOf && event.dataTransfer.types.indexOf("application/json") > -1 ? "application/json" : //Chrome & Safari
                    event.dataTransfer.types.contains && event.dataTransfer.types.contains("application/json") ? "application/json" : //Firefox
                        event.dataTransfer.types.contains && event.dataTransfer.types.contains("Text") ? "Text" : //IE
                            undefined;

                if (targetItem.foldersName && targetItem.foldersName !== 'trash')
                    return undefined;

                return dataField;
            }
        };

        $scope.dropTo = function (targetItem, $originalEvent) {
            var dataField = $scope.dropCondition(targetItem)($originalEvent)
            var originalItem = JSON.parse($originalEvent.dataTransfer.getData(dataField))

            if (targetItem.folderName === 'trash')
                $scope.dropTrash(originalItem);
            else
                $scope.dropMove(originalItem, targetItem);
        };

        $scope.dropMove = function (mail, folder) {
            var mailObj = new Mail()
            mailObj.id = mail.id
            mailObj.move(folder)
        }
        $scope.dropTrash = function (mail) {
            var mailObj = new Mail()
            mailObj.id = mail.id
            mailObj.trash()
        }

        //Given a data size in bytes, returns a more "user friendly" representation.
        $scope.getAppropriateDataUnit = quota.appropriateDataUnit;

        $scope.formatSize = function (size) {
            var formattedData = $scope.getAppropriateDataUnit(size)
            return (Math.round(formattedData.nb * 10) / 10) + " " + formattedData.order
        }


        $scope.postAttachments = function () {
            if (!$scope.newItem.id) {
                conversation.folders.draft.saveDraft($scope.newItem)
                    .then(() => $scope.newItem.postAttachments());
            } else {
                $scope.newItem.postAttachments();
            }
        }

        $scope.deleteAttachment = function (event, attachment, mail) {
            mail.deleteAttachment(attachment);
        }

        $scope.quota = quota;

        $scope.sortBy = {
            name: function (mail) {
                var systemFolder = $scope.getSystemFolder(mail)
                if (systemFolder === 'INBOX') {
                    if (mail.fromName)
                        return mail.fromName
                    else
                        return mail.sender().displayName
                }
                return _.chain(mail.displayNames)
                    .filter(function (u) { return mail.to.indexOf(u[0]) >= 0 })
                    .map(function (u) { return u[1] }).value().sort()
            },
            subject: function (mail) {
                return mail.subject
            },
            date: function (mail) {
                return mail.date
            },
            systemFolder: function (mail) {
                var systemFolder = $scope.getSystemFolder(mail)
                if (systemFolder === "INBOX")
                    return 1
                if (systemFolder === "OUTBOX")
                    return 2
                if (systemFolder === "DRAFT")
                    return 3
                return 0
            }
        }
        $scope.setSort = function (box, sortFun) {
            if (box.sort === sortFun) {
                box.reverse = !box.reverse
            } else {
                box.sort = sortFun
                box.reverse = false
            }
        }
    }]);