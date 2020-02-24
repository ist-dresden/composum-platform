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
                _view: '_view',
                _process: '_process',
                _state: '_state .badge',
                _progress: '_progress .progress-bar'
            },
            url: {
                base: '/libs/composum/platform/services/replication',
                _status: '/status',
                _reload: '.reload'
            },
            timeout: 5000
        });

        replication.ControlDialog = components.FormDialog.extend({

            initialize: function (options) {
                this.configNode = options.configNode;
                this.data = {
                    path: options.path,
                    type: options.type
                };
                components.FormDialog.prototype.initialize.call(this, options);
                this.$('button.delete').click(_.bind(this.deleteConfig, this));
            },

            deleteConfig: function () {
                core.openFormDialog(replication.const.url.delete + this.data.path, components.FormDialog,
                    undefined, undefined, _.bind(function () {
                        this.configNode.setupForm.configSetup.reload();
                    }, this));
            }
        });

        replication.Button = Backbone.View.extend({

            initialize: function (options) {
                var c = replication.const.css;
            },

            onClick: function (event) {
                event.preventDefault();
                core.openFormDialog(replication.const.url.create + this.configSetup.$el.data('path'),
                    replication.CreateDialog, {
                        setupForm: this,
                        path: this.configSetup.$el.data('path')
                    }, undefined, _.bind(function () {
                        this.configSetup.reload();
                    }, this));
                return false;
            }
        });

        replication.Process = Backbone.View.extend({

            initialize: function (options) {
                var c = replication.const.css;
                this.state = JSON.parse(atob(this.$el.data('state')));
                this.$state = this.$('.' + c.base + c._state);
                this.$progress = this.$('.' + c.base + c._progress);
            },

            refresh: function (state) {
                this.$state.removeClass().addClass('badge ' + state.state);
                core.i18n.get(state.state, _.bind(function (value) {
                    this.$state.text(value);
                }, this));
                this.$progress.css('width', state.progress + '%').text(state.progress + '%');
                this.state = state;
            }
        });

        replication.Status = Backbone.View.extend({

            initialize: function (options) {
                this.initContent();
            },

            initContent: function () {
                if (this.tmHandle) {
                    window.clearTimeout(this.tmHandle);
                }
                var c = replication.const.css;
                this.$view = this.$('.' + c.base + c._view);
                this.state = JSON.parse(atob(this.$view.data('state')));
                this.$state = this.$('.' + c.base + c._state);
                this.$progress = this.$('.' + c.base + c._progress);
                var that = this;
                var processes = this.processes = {};
                this.$('.' + c.base + c._process).each(function () {
                    var process = core.getView($(this), replication.Process, {status: that});
                    processes[process.state.id] = process;
                });
                if (this.state.state !== 'synchron') {
                    this.tmHandle = window.setTimeout(_.bind(this.refresh, this), replication.const.timeout);
                }
            },

            refresh: function () {
                var u = replication.const.url;
                core.getJson(u.base + u._status + '.json' + this.$view.data('path'),
                    _.bind(function (state) {
                        this.$state.removeClass().addClass('badge ' + state.state);
                        core.i18n.get(state.state, _.bind(function (value) {
                            this.$state.text(value);
                        }, this));
                        this.$progress.css('width', state.progress + '%').text(state.progress + '%');
                        for (var i = 0; i < state.processes.length; i++) {
                            var process = this.processes[state.processes[i].id];
                            if (process) {
                                process.refresh(state.processes[i]);
                            } else {
                                this.reload();
                                return;
                            }
                        }
                        this.state = state;
                        if (this.state.state !== 'synchron') {
                            this.tmHandle = window.setTimeout(_.bind(this.refresh, this), replication.const.timeout);
                        }
                    }, this));
            },

            reload: function () {
                var u = replication.const.url;
                core.getHtml(u.base + u._status + u._reload + '.html' + this.$el.data('path'),
                    _.bind(function (content) {
                        this.$el.html(content);
                        this.initContent();
                    }, this));
            }
        });

    })(CPM.platform.services.replication, CPM.core.components, CPM.core);

})();
