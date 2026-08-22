package dev.magnor.kompakt.data.fake

import dev.magnor.kompakt.domain.Action
import dev.magnor.kompakt.domain.ActionStyle
import dev.magnor.kompakt.domain.ActionType
import dev.magnor.kompakt.domain.Agent
import dev.magnor.kompakt.domain.AgentRun
import dev.magnor.kompakt.domain.AgentRunStatus
import dev.magnor.kompakt.domain.AgentStatus
import dev.magnor.kompakt.domain.Area
import dev.magnor.kompakt.domain.CalendarEvent
import dev.magnor.kompakt.domain.ChatThread
import dev.magnor.kompakt.domain.EntityKind
import dev.magnor.kompakt.domain.InboxItem
import dev.magnor.kompakt.domain.InboxPriority
import dev.magnor.kompakt.domain.Message
import dev.magnor.kompakt.domain.MessageRole
import dev.magnor.kompakt.domain.Note
import dev.magnor.kompakt.domain.Project
import dev.magnor.kompakt.domain.ProjectStatus
import dev.magnor.kompakt.domain.Task
import dev.magnor.kompakt.domain.TaskStatus
import kotlinx.datetime.Instant

/**
 * Deterministic baseline data for the fake repositories (replaces Phase 1's
 * string-only MockData). All timestamps anchor to [NOW] so unit tests and
 * UI previews are stable across runs.
 */
object FakeData {

    /** Saturday 2026-08-22, 18:00 UTC — the fake "now". */
    val NOW: Instant = Instant.parse("2026-08-22T18:00:00Z")

    private val yesterday = Instant.parse("2026-08-21T20:00:00Z")
    private val threeDaysAgo = Instant.parse("2026-08-19T09:00:00Z")

    val calendarEvents = listOf(
        CalendarEvent(
            id = "event_001",
            title = "Philosophy",
            startAt = Instant.parse("2026-08-22T09:00:00Z"),
            endAt = Instant.parse("2026-08-22T10:30:00Z"),
            location = "HG N-301",
        ),
        CalendarEvent(
            id = "event_002",
            title = "Dentist",
            startAt = Instant.parse("2026-08-22T12:30:00Z"),
            endAt = Instant.parse("2026-08-22T13:15:00Z"),
        ),
    )

    val tasks = listOf(
        Task(
            id = "task_001", title = "Review PR", status = TaskStatus.OPEN,
            dueAt = Instant.parse("2026-08-22T17:00:00Z"),
            projectId = "project_001", updatedAt = Instant.parse("2026-08-22T14:00:00Z"),
        ),
        Task(
            id = "task_002", title = "Buy groceries", status = TaskStatus.OPEN,
            dueAt = Instant.parse("2026-08-22T18:30:00Z"),
            areaId = "area_004", updatedAt = Instant.parse("2026-08-22T12:00:00Z"),
        ),
        Task(
            id = "task_003", title = "Send application", status = TaskStatus.OPEN,
            dueAt = Instant.parse("2026-08-22T20:00:00Z"),
            projectId = "project_002", updatedAt = Instant.parse("2026-08-21T18:00:00Z"),
        ),
        Task(
            id = "task_004", title = "Dentist", status = TaskStatus.OPEN,
            dueAt = Instant.parse("2026-08-22T12:30:00Z"),
            areaId = "area_001", updatedAt = Instant.parse("2026-08-20T08:00:00Z"),
        ),
        Task(
            id = "task_005", title = "Submit course application", status = TaskStatus.OPEN,
            dueAt = Instant.parse("2026-08-21T23:59:00Z"),
            projectId = "project_002", updatedAt = Instant.parse("2026-08-21T10:00:00Z"),
        ),
        Task(
            id = "task_006", title = "Renew passport", status = TaskStatus.OPEN,
            dueAt = Instant.parse("2026-08-25T12:00:00Z"),
            areaId = "area_003", updatedAt = Instant.parse("2026-08-18T16:00:00Z"),
        ),
    )

    val agents = listOf(
        Agent(
            id = "agent_001", name = "Kompakt audit", status = AgentStatus.RUNNING,
            description = "Audits spec/code drift across the Kompakt docs",
            capabilities = listOf("read-repo", "compare-docs"),
            lastActivity = Instant.parse("2026-08-22T17:48:00Z"),
            updatedAt = NOW,
        ),
        Agent(
            id = "agent_002", name = "Job Search", status = AgentStatus.IDLE,
            description = "Monitors job listings and drafts applications",
            capabilities = listOf("web-read", "draft"),
            lastActivity = Instant.parse("2026-08-22T15:52:00Z"),
            updatedAt = NOW,
        ),
        Agent(
            id = "agent_003", name = "Fedora Research", status = AgentStatus.FINISHED,
            description = "One-shot research worker",
            capabilities = listOf("web-read"),
            lastActivity = yesterday,
            updatedAt = NOW,
        ),
        Agent(
            id = "agent_004", name = "PR Reviewer", status = AgentStatus.WAITING_FOR_INPUT,
            description = "Reviews PRs, asks before risky suggestions",
            capabilities = listOf("read-repo", "comment"),
            lastActivity = Instant.parse("2026-08-22T16:30:00Z"),
            updatedAt = NOW,
        ),
    )

    val agentRuns = listOf(
        AgentRun(
            id = "run_001", agentId = "agent_001", title = "Spec drift check",
            objective = "Check cross-spec drift between docs/ and code",
            status = AgentRunStatus.RUNNING,
            startedAt = Instant.parse("2026-08-22T17:41:00Z"),
            updatedAt = Instant.parse("2026-08-22T17:52:00Z"),
            resultSummary = "3 findings so far",
        ),
        AgentRun(
            id = "run_002", agentId = "agent_004", title = "PR audit",
            objective = "Audit PR #55 for the task-system integration",
            status = AgentRunStatus.SUCCEEDED,
            startedAt = Instant.parse("2026-08-22T14:00:00Z"),
            updatedAt = Instant.parse("2026-08-22T15:10:00Z"),
            resultSummary = "LGTM with 2 minor comments. Left review on GitHub.",
        ),
        AgentRun(
            id = "run_003", agentId = "agent_002", title = "Course research",
            objective = "Research summer courses relevant to the degree plan",
            status = AgentRunStatus.WAITING_FOR_INPUT,
            startedAt = Instant.parse("2026-08-22T13:20:00Z"),
            updatedAt = Instant.parse("2026-08-22T16:05:00Z"),
            resultSummary = "Found 3 candidates — need your ranking before applying.",
            requiresInput = true,
        ),
    )

    val projects = listOf(
        Project(
            id = "project_001", name = "Evershift", status = ProjectStatus.ACTIVE,
            currentGoal = "Ship vertical slice milestone",
            nextAction = "Review PR", attentionCount = 2,
            updatedAt = Instant.parse("2026-08-22T10:00:00Z"),
        ),
        Project(
            id = "project_002", name = "KodeVerket", status = ProjectStatus.ACTIVE,
            currentGoal = "Task-system rollout",
            nextAction = "Send application", attentionCount = 1,
            updatedAt = Instant.parse("2026-08-21T09:00:00Z"),
        ),
        Project(
            id = "project_003", name = "Kompakt-Interface", status = ProjectStatus.ACTIVE,
            currentGoal = "v0.1 on real hardware",
            nextAction = "Finish Phase 2 domain model", attentionCount = 0,
            updatedAt = NOW,
        ),
        Project(
            id = "project_004", name = "dev-server", status = ProjectStatus.ACTIVE,
            currentGoal = "Keep the control plane boring",
            attentionCount = 0,
            updatedAt = Instant.parse("2026-08-20T11:00:00Z"),
        ),
    )

    val areas = listOf(
        Area(id = "area_001", name = "Health", description = "training, supplements",
            updatedAt = Instant.parse("2026-08-01T09:00:00Z")),
        Area(id = "area_002", name = "Career", description = "applications, KodeVerket",
            updatedAt = Instant.parse("2026-08-05T09:00:00Z")),
        Area(id = "area_003", name = "Personal", description = "philosophy, travel",
            updatedAt = Instant.parse("2026-08-10T09:00:00Z")),
        Area(id = "area_004", name = "Economy", description = "budget, subscriptions",
            updatedAt = Instant.parse("2026-08-12T09:00:00Z")),
    )

    val notes = listOf(
        Note(
            id = "note_001",
            text = "Look into agent UI idea — separate Agents (process manager) from Chat (conversation).",
            createdAt = Instant.parse("2026-08-22T16:00:00Z"),
            updatedAt = Instant.parse("2026-08-22T16:00:00Z"),
        ),
        Note(
            id = "note_002",
            text = "Philosophy thought — determinism as a property of models, not of the world.",
            createdAt = yesterday, updatedAt = yesterday,
        ),
        Note(
            id = "note_003",
            text = "KodeVerket pricing — value-based tiers beat hourly for retained clients.",
            createdAt = threeDaysAgo, updatedAt = threeDaysAgo,
        ),
    )

    val inboxItems = listOf(
        InboxItem(
            id = "inbox_001", sourceType = EntityKind.AGENT_RUN, sourceId = "run_003",
            title = "Agent needs approval", summary = "Course research · ranking",
            timestamp = Instant.parse("2026-08-22T16:05:00Z"), priority = InboxPriority.HIGH,
            actions = listOf(
                Action(ActionType.APPROVE, style = ActionStyle.PRIMARY),
                Action(ActionType.REJECT, style = ActionStyle.DESTRUCTIVE),
            ),
        ),
        InboxItem(
            id = "inbox_002", sourceType = EntityKind.TASK, sourceId = "task_005",
            title = "Task overdue", summary = "Submit course application",
            timestamp = Instant.parse("2026-08-22T00:01:00Z"), priority = InboxPriority.HIGH,
            actions = listOf(Action(ActionType.COMPLETE), Action(ActionType.POSTPONE)),
        ),
        InboxItem(
            id = "inbox_003", sourceType = EntityKind.AGENT_RUN, sourceId = "run_002",
            title = "Agent finished", summary = "PR audit",
            timestamp = Instant.parse("2026-08-22T15:10:00Z"), priority = InboxPriority.NORMAL,
            actions = listOf(Action(ActionType.OPEN_RESULT)),
        ),
        InboxItem(
            id = "inbox_004",
            title = "System warning", summary = "Server backup failed",
            timestamp = Instant.parse("2026-08-22T07:30:00Z"), priority = InboxPriority.LOW,
        ),
    )

    val chatThreads = listOf(
        ChatThread(
            id = "thread_001", title = "General",
            createdAt = Instant.parse("2026-08-20T10:00:00Z"),
            updatedAt = Instant.parse("2026-08-22T12:04:00Z"),
            lastMessagePreview = "Then Y's queue depth stays flat while X degrades linearly.",
        ),
        ChatThread(
            id = "thread_002", title = "KodeVerket ideas",
            createdAt = Instant.parse("2026-08-18T09:00:00Z"),
            updatedAt = Instant.parse("2026-08-21T20:00:00Z"),
            lastMessagePreview = "Pricing model sketch…",
        ),
        ChatThread(
            id = "thread_003", title = "Linux questions",
            createdAt = Instant.parse("2026-08-17T09:00:00Z"),
            updatedAt = Instant.parse("2026-08-21T09:30:00Z"),
            lastMessagePreview = "systemd timer vs cron",
        ),
        ChatThread(
            id = "thread_004", title = "Health",
            createdAt = Instant.parse("2026-08-14T09:00:00Z"),
            updatedAt = Instant.parse("2026-08-17T15:00:00Z"),
            lastMessagePreview = "Supplement stack check",
        ),
        ChatThread(
            id = "thread_005", title = "Travel research",
            createdAt = Instant.parse("2026-08-10T09:00:00Z"),
            updatedAt = Instant.parse("2026-08-16T11:00:00Z"),
            lastMessagePreview = "Japan in autumn?",
        ),
    )

    val chatMessages = listOf(
        Message(
            id = "msg_001", chatId = "thread_001", role = MessageRole.USER,
            content = "How does X compare to Y?",
            createdAt = Instant.parse("2026-08-22T12:00:00Z"),
        ),
        Message(
            id = "msg_002", chatId = "thread_001", role = MessageRole.ASSISTANT,
            content = "X is simpler but Y scales better for your case because the queue absorbs bursts.",
            createdAt = Instant.parse("2026-08-22T12:01:00Z"),
        ),
        Message(
            id = "msg_003", chatId = "thread_001", role = MessageRole.USER,
            content = "And what if the load doubles?",
            createdAt = Instant.parse("2026-08-22T12:03:00Z"),
        ),
        Message(
            id = "msg_004", chatId = "thread_001", role = MessageRole.ASSISTANT,
            content = "Then Y's queue depth stays flat while X degrades linearly.",
            createdAt = Instant.parse("2026-08-22T12:04:00Z"),
        ),
    )
}
