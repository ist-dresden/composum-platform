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
                _process: '_process',
                _state: '_state .badge',
                _terminate: '_terminate',
                _synchronize: '_synchronize',
                _progress: '_progress .progress-bar',
                _timestamp: '_timestamp'
            },
            url: {
                base: '/libs/composum/platform/services/replication',
                _summary: '/status.summary',
                _status: '/status',
                _reload: '.reload'
            },
            polling: 5000
        });

        replication.Badge = Backbone.View.extend({

            initialize: function (options) {
                this.state = JSON.parse(atob(this.$el.data('state')));
                this.resumeRefresh();
            },

            stopRefresh: function () {
                if (this.tmRefresh) {
                    window.clearTimeout(this.tmRefresh);
                    this.tmRefresh = undefined;
                }
            },

            resumeRefresh: function () {
                if (!this.tmRefresh && this.state.state !== 'synchron') {
                    this.tmRefresh = window.setTimeout(_.bind(this.refresh, this), replication.const.polling);
                }
            },

            refresh: function () {
                this.tmRefresh = undefined;
                var u = replication.const.url;
                core.getJson(u.base + u._summary + '.' + this.state.stage + '.json' + this.$el.data('path'),
                    _.bind(function (state) {
                        var c = replication.const.css;
                        this.$el.removeClass().addClass(c.base + c._badge + ' widget badge badge-pill ' + state.state);
                        core.i18n.get(state.state, _.bind(function (value) {
                            this.$el.attr('title', value);
                        }, this));
                        this.state = state;
                        this.resumeRefresh();
                    }, this));
            }
        });

        CPM.widgets.register('.widget.' + replication.const.css.base + replication.const.css._badge, replication.Badge);

        replication.Process = Backbone.View.extend({

            initialize: function (options) {
                var c = replication.const.css;
                this.state = JSON.parse(atob(this.$el.data('state')));
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
                if (state.state === 'synchron' && this.state.state !== 'synchron') {
                    core.i18n.get('finished at', _.bind(function (value) {
                        this.$timestamp.find('.key').text(value);
                    }, this));
                    this.$timestamp.find('.value').text(state.finishedAt);
                }
                this.state = state;
            }
        });

        replication.Status = Backbone.View.extend({

            initialize: function (options) {
                this.initContent();
            },

            initContent: function () {
                this.stopRefresh();
                var c = replication.const.css;
                this.$view = this.$('.' + c.base + c._view);
                this.state = JSON.parse(atob(this.$view.data('state')));
                this.$state = this.$('.' + c.base + c._state);
                this.$progress = this.$('.' + c.base + c._progress);
                this.$terminate = this.$('.' + c.base + c._terminate);
                this.$synchronize = this.$('.' + c.base + c._synchronize);
                var that = this;
                var processes = this.processes = {};
                this.$('.' + c.base + c._process).each(function () {
                    var process = core.getView($(this), replication.Process);
                    processes[process.state.id] = process;
                });
                this.$terminate.find('button').click(_.bind(this.terminate, this));
                this.$synchronize.find('button').click(_.bind(this.synchronize, this));
                this.resumeRefresh();
            },

            terminate: function (event) {
                event.preventDefault();
                this.reload();
                return false;
            },

            synchronize: function (event) {
                event.preventDefault();
                this.reload();
                return false;
            },

            stopRefresh: function () {
                if (this.tmRefresh) {
                    window.clearTimeout(this.tmRefresh);
                    this.tmRefresh = undefined;
                }
            },

            resumeRefresh: function () {
                if (!this.tmRefresh && this.state.state !== 'synchron') {
                    this.tmRefresh = window.setTimeout(_.bind(this.refresh, this), replication.const.polling);
                }
            },

            refresh: function () {
                this.tmRefresh = undefined;
                var u = replication.const.url;
                core.getJson(u.base + u._status + '.' + this.state.stage + '.json' + this.$view.data('path'),
                    _.bind(function (data) {
                        var state = data.summary;
                        this.$state.removeClass().addClass('badge ' + state.state);
                        core.i18n.get(state.state, _.bind(function (value) {
                            this.$state.text(value);
                        }, this));
                        if (state.running) {
                            this.$terminate.removeClass('hidden');
                            this.$synchronize.addClass('hidden');
                        } else {
                            this.$terminate.addClass('hidden');
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
                        this.state = state;
                        this.resumeRefresh();
                    }, this));
            },

            reload: function () {
                var u = replication.const.url;
                core.getHtml(u.base + u._status + u._reload + '.html' + this.$view.data('path'),
                    _.bind(function (content) {
                        this.$el.html(content);
                        this.initContent();
                    }, this));
            }
        });

        CPM.widgets.register('.widget.' + replication.const.css.base, replication.Status);

        replication.PublishDialog = components.LoadedDialog.extend({

            initialize: function (options) {
                components.LoadedDialog.prototype.initialize.call(this, options);
                this.$('button.terminate').click(_.bind(this.terminate, this));
                this.$('button.publish').click(_.bind(this.publish, this));
            },

            terminate: function (event) {
                event.preventDefault();
                return false;
            },

            publish: function (event) {
                event.preventDefault();
                return false;
            }
        });

    })(CPM.platform.services.replication, CPM.core.components, CPM.core);

})();
