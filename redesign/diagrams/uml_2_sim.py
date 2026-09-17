"""UML 类图 2/4：sim 包 —— 时间推进与事件投递。

这张图的重点是 **依赖倒置接缝**：
sim 只定义 Sensors / TickObserver / EventSink / ClockPolicy 这几个“洞”，
由 agent、company 从外面实现；sim 不认识 Agent。
"""

from dsl import Dot, uml, note, comp, aggr, assoc, inherit, realize, depend, require_dot


def build():
    d = Dot("uml_sim", rankdir="TB", nodesep="0.42", ranksep="0.8",
            compound="true")

    # ================= 时钟 =================
    d.node("Clock", uml("Clock", "interface", kind="sim",
                        methods=["+ now() : Tick", "+ nowDay() : DayTick",
                                 "+ calendar() : ShiftCalendar",
                                 "+ currentDateTime() : String",
                                 "+ describe() : String"]))
    d.node("SimClock", uml("SimClock", kind="sim", tag="sim.clock",
                           attrs=["- calendar : ShiftCalendar",
                                  "- baseDate : LocalDate",
                                  "- tick     : long"],
                           methods=["+ advanceTicks(long) / + jumpTo(Tick)",
                                    "+ resetTo(DayTick) / + setBaseDate(LocalDate)"]))
    d.node("ShiftCalendar", uml("ShiftCalendar", kind="domain", stereotype="record",
                                tag="sim.clock",
                                attrs=["secondsPerTick / shiftStartHour / shiftEndHour"],
                                methods=["+ locate(Tick) / at(int,int) : DayTick",
                                         "+ withinShift(DayTick)",
                                         "+ ticksUntilShiftEnd(DayTick)",
                                         "+ nextShiftStart(DayTick)",
                                         "+ clockTime(DayTick) / describe(Tick)"]))
    d.node("Tick", uml("Tick", kind="domain", stereotype="record",
                       attrs=["value : long"],
                       methods=["+ plus(long) / before(Tick) / after(Tick)"]))
    d.node("DayTick", uml("DayTick", kind="domain", stereotype="record",
                          attrs=["day / tickOfDay : int"]))

    d.node("ClockDriver", uml("ClockDriver", kind="sim", tag="sim.clock",
                              attrs=["- clock    : SimClock",
                                     "- schedule : ScheduleTable",
                                     "- policy   : ClockPolicy",
                                     "- sink     : EventSink",
                                     "- sensors  : Sensors",
                                     "- observer : TickObserver",
                                     "- options  : ClockOptions"],
                              methods=["+ start() / stop()",
                                       "+ pause() / resume() / paused()",
                                       "+ tickOnce()",
                                       "+ nextFireTick() : Optional<Tick>",
                                       "record ClockOptions(busyPoll, idlePoll, wrapUpGrace)"]))
    d.node("ClockPolicy", uml("ClockPolicy", "interface", kind="sim",
                              methods=["+ next(ClockSignals, ShiftCalendar,",
                                       "       Tick, Optional<Tick>) : ClockAction",
                                       "record ClockSignals(anyBusy, allIdle, allOffDuty,",
                                       "                     wrapUpOverdue, idleMillis)",
                                       "sealed ClockAction = Advance | FastForwardTo",
                                       "                   | Hold | ForceWrapUp"]))
    d.node("DefaultClockPolicy", uml("DefaultClockPolicy", kind="sim",
                                     attrs=["- fastForwardIdleMillis : long"]))
    d.node("ScheduleTable", uml("ScheduleTable", kind="sim", tag="sim.clock",
                                attrs=["- entries : Map<ScheduleId, ScheduledEntry>"],
                                methods=["+ schedule(String, RoleId, DayTick, Payload)",
                                         "+ cancel(ScheduleId) / + reschedule(...)",
                                         "+ list(RoleId) : List<ScheduledEntry>",
                                         "+ due(Tick) : List<AgentEvent>",
                                         "+ nextFireTick(Tick) / + activateDay(int)"]))
    d.node("ReminderScheduler", uml("ReminderScheduler", "interface", kind="iface",
                                    methods=["+ schedule(String, RoleId, DayTick, Payload)",
                                             "+ cancel(ScheduleId)"]))

    # ================= 三个“洞” =================
    d.node("Sensors", uml("Sensors", "interface", kind="sim",
                          methods=["+ anyBusy() / + allIdle() / + allOffDuty()"]))
    d.node("TickObserver", uml("TickObserver", "interface", kind="sim",
                               methods=["+ onTick(Tick now)"]))
    d.node("EventSink", uml("EventSink", "interface", kind="event",
                            methods=["+ publish(AgentEvent)"]))
    d.node("Team", uml("Team", kind="agent", tag="agent · 花名册",
                       attrs=["- byId : Map<RoleId, Agent>"],
                       methods=["+ anyBusy() / + allIdle() / + allOffDuty()",
                                "（详见公司编排图）"]))
    d.node("ShiftDirector", uml("ShiftDirector", kind="company", tag="company",
                                attrs=["- team  : Team",
                                       "- clock : Clock",
                                       "- gate  : LifecycleGate"],
                                methods=["+ onTick(Tick)",
                                         "+ onShiftStart() / + onShiftEnd()",
                                         "+ forceWrapUp()"]))
    d.node("EventRouter", uml("EventRouter", kind="task", tag="agent.dispatch",
                              attrs=["- team / -delivery / -tasks / -transcript"],
                              methods=["+ publish(AgentEvent)",
                                       "+ publishAndReport(AgentEvent)",
                                       "    : Map<RoleId, Routed>"]))

    # ================= 事件 =================
    d.node("AgentEvent", uml("AgentEvent", kind="domain", stereotype="record",
                             tag="sim.event",
                             attrs=["id : EventId        kind : EventKind",
                                    "priority : Priority",
                                    "recipients : Set<RoleId>   空 = 广播",
                                    "payload : Payload",
                                    "fireAt : Optional<Tick>  定时事件",
                                    "occurredAt : Instant"],
                             methods=["+ broadcast(...) / toRole(...) / scheduled(...)",
                                      "+ targeted() / broadcast() / rescheduledTo(Tick)"]))
    d.node("EventKind", uml("EventKind", kind="domain", stereotype="record",
                            attrs=["source / name"],
                            methods=["SHIFT_START · SHIFT_END",
                                     "TASK_DUE · NEW_MAIL"]))
    d.node("Priority", uml("Priority", kind="domain", stereotype="enum",
                           methods=["LOW · NORMAL · HIGH · EMERGENCY",
                                    "+ weight() / ofWeight(int)"]))
    d.node("DeliveryPolicy", uml("DeliveryPolicy", "interface", kind="event",
                                 methods=["+ decide(DeliveryContext) : DeliveryDecision",
                                          "record DeliveryContext(event, spec, state,",
                                          "                        scheduledReminder)",
                                          "enum DeliveryVerdict = DELIVER|HOLD|DROP",
                                          "record DeliveryDecision(verdict, reason)"]))
    d.node("DefaultDeliveryPolicy", uml("DefaultDeliveryPolicy", kind="event",
                                        attrs=["- salience : SaliencePolicy"]))
    d.node("SaliencePolicy", uml("SaliencePolicy", "interface", kind="event",
                                 methods=["+ score(RoleSpec, AgentEvent)",
                                          "    : SalienceDecision(pass, score, relevance, reason)"]))
    d.node("KeywordSaliencePolicy", uml("KeywordSaliencePolicy", kind="event"))

    # ================= 关系 =================
    realize(d, "SimClock", "Clock")
    assoc(d, "SimClock", "ShiftCalendar", "1")
    depend(d, "Clock", "Tick")
    depend(d, "Clock", "DayTick")

    assoc(d, "ClockDriver", "SimClock", "1")
    aggr(d, "ClockDriver", "ScheduleTable")
    assoc(d, "ClockDriver", "ClockPolicy", "策略")
    assoc(d, "ClockDriver", "EventSink", "到期事件出口")
    assoc(d, "ClockDriver", "Sensors", "读忙闲")
    assoc(d, "ClockDriver", "TickObserver", "班次边界")
    realize(d, "DefaultClockPolicy", "ClockPolicy")
    realize(d, "ScheduleTable", "ReminderScheduler")
    depend(d, "ClockPolicy", "Tick")
    depend(d, "ScheduleTable", "AgentEvent", "due() 产出事件")

    realize(d, "Team", "Sensors", "跨包实现（唯一）")
    realize(d, "ShiftDirector", "TickObserver", "跨包实现（唯一）")
    realize(d, "EventRouter", "EventSink", "跨包实现（唯一）")

    assoc(d, "EventRouter", "AgentEvent")
    assoc(d, "EventRouter", "DeliveryPolicy", "逐收件人裁决")
    realize(d, "DefaultDeliveryPolicy", "DeliveryPolicy")
    comp(d, "DefaultDeliveryPolicy", "SaliencePolicy")
    realize(d, "KeywordSaliencePolicy", "SaliencePolicy")
    depend(d, "DeliveryPolicy", "AgentEvent", "DeliveryContext.event")
    depend(d, "SaliencePolicy", "AgentEvent")
    comp(d, "AgentEvent", "EventKind")
    comp(d, "AgentEvent", "Priority")

    d.node("legend", note("图例 · 颜色 = 包",
                          ["紫 = sim.clock      玫 = sim.event",
                           "绿 = agent（实现 sim 的洞）",
                           "青 = company        靛 = agent.dispatch",
                           "黄 = record / enum",
                           "◆ 组合  ◇ 聚合  ▶ 关联",
                           "┄▷ 实现  ┄▶ 依赖",
                           "",
                           "sim ⇢ agent 的边全部消失：",
                           "sim 只认识 5 个接口，不认识 Agent"],
                          kind="neutral"))

    require_dot()
    d.render("uml-2-sim")
