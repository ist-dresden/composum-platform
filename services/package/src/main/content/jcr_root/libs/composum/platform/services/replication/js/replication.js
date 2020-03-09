/**
 *
 *
 */
(function () {
    'use strict';
    CPM.namespace('platform.services.replication');

    (function (replication, components, core) {

        replication.const = _.extend(replication.const || {}, {
            css: {
                base: 'composum-platform-replication-status',
                _badge: '_badge',
                _view: '_view',
                _stage: '_stage',
                _title: '_title',
                _process: '_process',
                _state: '_state .badge',
                _abort: '_abort',
                _synchronize: '_synchronize',
                _progress: '_progress .progress-bar',
                _timestamp: '_timestamp',
                dialog: 'composum-platform-replication-dialog',
                _footer: '_footer'
            },
            url: {
                servlet: '/bin/cpm/platform/staging',
                _publish: '.stageRelease',
                _release: 'releaseKey=',
                _abort: '.abortReplication',
                base: '/libs/composum/platform/services/replication',
                _dialog: '/dialog',
                _summary: '/status.summary',
                _status: '/status',
                _reload: '.reload'
            },
            polling: {
                running: 2000, idle: 10000
            },
            dialog: 'normal'
        });

        /**
         * @abstract triggers the abstract 'refresh()' function while state is 'running'
         */
        replication.StateMonitor = Backbone.View.extend({

            stopRefresh: function () {
                if (this.tmRefresh) {
                    window.clearTimeout(this.tmRefresh);
                    this.tmRefresh = undefined;
                }
            },

            resumeRefresh: function () {
                if (!this.tmRefresh) {
                    this.tmRefresh = window.setTimeout(_.bind(this.refresh, this),
                        replication.const.polling[this.data.state.state === 'running' ? 'running' : 'idle']);
                }
            }
        });

        replication.Badge = replication.StateMonitor.extend({

            initialize: function (options) {
                this.data = {
                    state: JSON.parse(atob(this.$el.data('state')))
                };
                this.resumeRefresh();
            },

            refresh: function () {
                this.tmRefresh = undefined;
                var u = replication.const.url;
                core.getJson(u.base + u._summary + '.' + this.data.state.stage + '.json' + this.$el.data('path'),
                    _.bind(function (state) {
                        var c = replication.const.css;
                        this.$el.removeClass().addClass(c.base + c._badge + ' widget badge badge-pill ' + state.state);
                        core.i18n.get([state.stage, state.state], _.bind(function (value) {
                            this.$el.attr('title', value[0] + ': ' + value[1]);
                        }, this));
                        this.data.state = state;
                        this.resumeRefresh();
                    }, this));
            }
        });

        CPM.widgets.register('.widget.' + replication.const.css.base + replication.const.css._badge, replication.Badge);

        replication.Process = Backbone.View.extend({

            initialize: function (options) {
                var c = replication.const.css;
                this.data = {
                    state: JSON.parse(atob(this.$el.data('state')))
                };
                this.$state = this.$('.' + c.base + c._state);
                this.$progress = this.$('.' + c.base + c._progress);
                this.$timestamp = this.$('.' + c.base + c._timestamp);
            },

            refresh: function (state) {
                this.$state.removeClass().addClass('badge ' + state.state);
                core.i18n.get(state.state, _.bind(function (value) {
                    this.$state.text(value);
                }, this));
                this.$progress
                    .css('width', state.progress + '%')
                    .attr('aria-valuenow', state.progress)
                    .text(state.progress + '%');
                if (state.state === 'synchron' && this.data.state.state !== 'synchron') {
                    core.i18n.get('finished at', _.bind(function (value) {
                        this.$timestamp.find('.key').text(value);
                    }, this));
                    this.$timestamp.find('.value').text(state.finishedAt);
                }
                this.data.state = state;
            }
        });

        replication.Status = replication.StateMonitor.extend({

            initialize: function (options) {
                this.listeners = [];
                this.initContent();
            },

            initContent: function () {
                this.stopRefresh();
                var c = replication.const.css;
                this.$view = this.$('.' + c.base + c._view);
                this.data = {
                    path: this.$view.data('path'),
                    stage: this.$view.data('stage'),
                    release: this.$view.data('release'),
                    releaseKey: this.$view.data('key'),
                    releaseLabel: this.$view.data('label'),
                    state: JSON.parse(atob(this.$view.data('state')))
                };
                this.$stage = this.$('.' + c.base + c._stage);
                this.$state = this.$stage.find('.' + c.base + c._state);
                this.$title = this.$stage.find('.' + c.base + c._title);
                this.$progress = this.$('.' + c.base + c._progress);
                this.$abort = this.$('.' + c.base + c._abort);
                this.$synchronize = this.$('.' + c.base + c._synchronize);
                var that = this;
                var processes = this.processes = {};
                this.$('.' + c.base + c._process).each(function () {
                    var process = core.getView($(this), replication.Process);
                    processes[process.data.state.id] = process;
                });
                this.$abort.find('button').click(_.bind(this.abort, this));
                this.$synchronize.find('button').click(_.bind(this.synchronize, this));
                this.propagateRefresh();
                this.resumeRefresh();
            },

            setLabel: function (label) {
                this.$title.text(label);
            },

            addListener: function (callback) {
                this.listeners = _.union(this.listeners, [callback]);
            },

            removeListener: function (callback) {
                this.listeners = _.without(this.listeners, [callback]);
            },

            propagateRefresh: function (callback) {
                this.listeners.forEach(function (callback) {
                    callback(this);
                }, this);
            },

            abort: function (event) {
                event.preventDefault();
                this.openPublishDialog(this.data.release, _.bind(this.reload, this));
                return false;
            },

            synchronize: function (event) {
                event.preventDefault();
                this.openPublishDialog(this.data.release, _.bind(this.reload, this));
                return false;
            },

            openPublishDialog: function (releasePath, callback) {
                var u = replication.const.url;
                var url = u.base + u._dialog + '.' + this.data.stage + '.html' + releasePath;
                core.openFormDialog(url, replication.PublishDialog, {
                        path: this.data.release,
                        stage: this.data.stage,
                        currentKey: this.data.releaseKey,
                        currentLabel: this.data.releaseLabel,
                        targetKey: this.data.releaseKey,
                        tagretLabel: this.data.releaseLabel
                    }, undefined,
                    _.bind(function () {
                        if (_.isFunction(callback)) {
                            callback();
                        }
                    }, this));
            },

            refresh: function () {
                this.tmRefresh = undefined;
                var u = replication.const.url;
                core.getJson(u.base + u._status + '.' + this.data.state.stage + '.json' + this.data.path,
                    _.bind(function (data) {
                        var state = data.summary;
                        this.$state.removeClass().addClass('badge badge-pill ' + state.state);
                        core.i18n.get(state.state, _.bind(function (value) {
                            this.$state.text(value);
                        }, this));
                        if (state.running) {
                            this.$abort.removeClass('hidden');
                            this.$synchronize.addClass('hidden');
                        } else {
                            this.$abort.addClass('hidden');
                            this.$synchronize.removeClass('hidden');
                        }
                        this.$progress
                            .css('width', state.progress + '%')
                            .attr('aria-valuenow', state.progress)
                            .text(state.progress + '%');
                        for (var i = 0; i < data.processes.length; i++) {
                            var process = this.processes[data.processes[i].id];
                            if (process) {
                                process.refresh(data.processes[i]);
                            } else {
                                this.reload();
                                return;
                            }
                        }
                        this.data.state = state;
                        this.propagateRefresh();
                        this.resumeRefresh();
                    }, this));
            },

            reload: function () {
                var u = replication.const.url;
                core.getHtml(u.base + u._status + u._reload + '.' + this.data.state.stage + '.html' + this.data.path,
                    _.bind(function (content) {
                        this.$el.html(content);
                        this.initContent();
                    }, this));
            }
        });

        CPM.widgets.register('.widget.' + replication.const.css.base, replication.Status);

        /**
         * @param options{path,stage,targetKey,targetLabel,currentKey,currentLabel}
         */
        replication.PublishDialog = components.LoadedDialog.extend({

            initialize: function (options) {
                var c = replication.const.css;
                components.LoadedDialog.prototype.initialize.call(this, options);
                this.data = {
                    path: options.path || this.$el.data('path'),
                    stage: options.stage || this.$el.data('stage'),
                    targetKey: options.targetKey,
                    targetLabel: options.targetLabel,
                    currentKey: options.currentKey,
                    currentLabel: options.currentLabel
                };
                this.status = core.getWidget(this.$el, '.' + c.base, replication.Status);
                var $footer = this.$('.' + c.dialog + c._footer);
                this.$abort = $footer.find('button.abort');
                this.$cancel = $footer.find('button.cancel');
                this.$publish = $footer.find('button.publish');
                this.$close = $footer.find('button.exit');
                if (this.data.targetKey === this.data.currentKey) {
                    core.i18n.get('Synchronize', _.bind(function (value) {
                        this.$publish.text(value);
                    }, this));
                }
                this.refresh();
                this.status.addListener(_.bind(this.refresh, this));
                this.$abort.click(_.bind(this.abort, this));
                this.$publish.click(_.bind(this.publish, this));
                this.$close.click(_.bind(this.hide, this));
            },

            /**
             * @listens status: adjusts the button states
             */
            refresh: function () {
                if (this.data.currentLabel) {
                    this.status.setLabel(this.data.currentLabel);
                }
                if (this.status.data.state.state === 'running') {
                    this.$abort.removeClass('hidden');
                    this.$publish.attr('disabled', 'disabled');
                } else {
                    this.$abort.addClass('hidden');
                    this.$publish.removeAttr('disabled');
                }
            },

            abort: function (event) {
                event.preventDefault();
                var u = replication.const.url;
                var url = u.servlet + u._abort + '.' + this.data.stage + '.json' + this.data.path;
                core.ajaxPost(url, {
                        releaseKey: this.data.targetKey
                    }, {}, _.bind(function (result) {
                        this.status.refresh();
                    }, this)
                );
                return false;
            },

            publish: function (event) {
                event.preventDefault();
                var u = replication.const.url;
                var url = u.servlet + u._publish + '.' + this.data.stage + '.json' + this.data.path;
                core.ajaxPost(url, {
                        releaseKey: this.data.targetKey
                    }, {}, _.bind(function (result) {
                        if (replication.const.dialog === 'normal') {
                            // close like a normal dialog
                            this.hide();
                        } else {
                            // let it open to show replication status changes
                            this.$cancel.addClass('hidden');
                            this.$close.removeClass('hidden');
                            this.$publish.removeClass('btn-primary').addClass('btn-default');
                            this.status.reload();
                        }
                    }, this)
                );
                return false;
            }
        });

    })(CPM.platform.services.replication, CPM.core.components, CPM.core);

})();
