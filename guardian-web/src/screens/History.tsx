import { useEffect, useMemo, useState } from "react";
import { Link } from "react-router-dom";
import { errorText, guardianApi } from "../api";
import { TabBar } from "../components/TabBar";
import { averageResponseMinutes, countsInWindow, eventLine, formatClock, formatKoreanDate, weekMarks, type WeekMark } from "../format";
import { IconAlert, IconCheck } from "../icons";
import { useSession } from "../session";
import type { EventSummary, EventType } from "../types";

type Filter = "all" | "done" | "open";

type Row = {
  id: string;
  date: string;
  time: string;
  text: string;
  type: EventType;
};

const mockRows: Row[] = [
  { id: "oct21", date: "10월 21일", time: "오전 9:12", text: "2분 뒤 확인", type: "responded" },
  { id: "oct9", date: "10월 9일", time: "오후 7:30", text: "‘가고 있어요’", type: "responded" },
  { id: "sep28", date: "9월 28일", time: "새벽 2:05", text: "아무도 안 봤어요", type: "fall_alert" },
  { id: "sep3", date: "9월 3일", time: "오후 1:47", text: "5분 뒤 확인", type: "responded" },
];

function MockChart() {
  return (
    <svg width="100%" height="96" viewBox="0 0 342 96" role="img" aria-label="주별 알림 예시">
      <line x1="8" x2="22" y1="70" y2="70" stroke="var(--c-sub)" strokeWidth="2" strokeLinecap="round" />
      <line x1="36" x2="50" y1="70" y2="70" stroke="var(--c-sub)" strokeWidth="2" strokeLinecap="round" />
      <path d="M64,70 q-1,-30 1,-58 q8,-2 14,1 q1,28 0,57z" fill="var(--c-btn)" stroke="var(--c-text)" strokeWidth="2" />
      <text x="71" y="90" fontSize="15" textAnchor="middle" fill="#4A554D">
        8/3
      </text>
      <line x1="92" x2="106" y1="70" y2="70" stroke="var(--c-sub)" strokeWidth="2" strokeLinecap="round" />
      <line x1="120" x2="134" y1="70" y2="70" stroke="var(--c-sub)" strokeWidth="2" strokeLinecap="round" />
      <line x1="148" x2="162" y1="70" y2="70" stroke="var(--c-sub)" strokeWidth="2" strokeLinecap="round" />
      <path d="M176,70 q-1,-30 1,-58 q8,-2 14,1 q1,28 0,57z" fill="var(--c-btn)" stroke="var(--c-text)" strokeWidth="2" />
      <text x="183" y="90" fontSize="15" textAnchor="middle" fill="#4A554D">
        9/3
      </text>
      <line x1="204" x2="218" y1="70" y2="70" stroke="var(--c-sub)" strokeWidth="2" strokeLinecap="round" />
      <line x1="232" x2="246" y1="70" y2="70" stroke="var(--c-sub)" strokeWidth="2" strokeLinecap="round" />
      <path d="M260,70 q-1,-30 1,-58 q8,-2 14,1 q1,28 0,57z" fill="var(--c-warn)" stroke="var(--c-text)" strokeWidth="2" />
      <text x="267" y="90" fontSize="15" textAnchor="middle" fill="#4A554D">
        9/28
      </text>
      <line x1="288" x2="302" y1="70" y2="70" stroke="var(--c-sub)" strokeWidth="2" strokeLinecap="round" />
      <path d="M316,70 q-1,-30 1,-58 q8,-2 14,1 q1,28 0,57z" fill="var(--c-btn)" stroke="var(--c-text)" strokeWidth="2" />
      <text x="323" y="90" fontSize="15" textAnchor="middle" fill="#4A554D">
        10/21
      </text>
    </svg>
  );
}

function LiveChart({ weeks }: { weeks: WeekMark[] }) {
  return (
    <svg width="100%" height="96" viewBox="0 0 342 96" role="img" aria-label="최근 12주 알림">
      {weeks.map((week, index) => {
        const x = 8 + index * 28;
        if (week.count === 0) {
          return <line key={index} x1={x} x2={x + 14} y1="70" y2="70" stroke="var(--c-sub)" strokeWidth="2" strokeLinecap="round" />;
        }
        return (
          <g key={index}>
            <path d={`M${x},70 q-1,-30 1,-58 q8,-2 14,1 q1,28 0,57z`} fill={week.open ? "var(--c-warn)" : "var(--c-btn)"} stroke="var(--c-text)" strokeWidth="2" />
            <text x={x + 8} y="90" fontSize="13" textAnchor="middle" fill="#4A554D">
              {week.label}
            </text>
          </g>
        );
      })}
    </svg>
  );
}

export function HistoryScreen() {
  const { mock, token, mockState, setToken } = useSession();
  const [events, setEvents] = useState<EventSummary[] | null>(mock ? [] : null);
  const [filter, setFilter] = useState<Filter>("all");
  const [error, setError] = useState("");

  useEffect(() => {
    document.title = "기록 · 낙상제로";
    if (mock || !token) return;
    guardianApi
      .events(token)
      .then(setEvents)
      .catch((err) => {
        if (err instanceof Error && "status" in err && (err as { status: number }).status === 401) setToken(null);
        else setError(errorText(err));
      });
  }, [mock, token, setToken]);

  const rows = useMemo(() => {
    if (mock) {
      return mockRows.map((row) =>
        mockState.ackedIds.includes(row.id) && row.type === "fall_alert"
          ? { ...row, type: "responded" as const, text: "‘가고 있어요’" }
          : row,
      );
    }
    return (events ?? []).map((event) => ({
      id: event.id,
      date: formatKoreanDate(event.detectedAt),
      time: formatClock(event.detectedAt),
      text: eventLine(event),
      type: event.type,
    }));
  }, [mock, mockState.ackedIds, events]);

  const visible = rows.filter((row) => {
    if (filter === "done") return row.type === "responded";
    if (filter === "open") return row.type === "fall_alert";
    return true;
  });

  const summary = mock
    ? {
        total: rows.filter((row) => row.type !== "self_cancelled").length,
        done: rows.filter((row) => row.type === "responded").length,
        open: rows.filter((row) => row.type === "fall_alert").length,
        average: rows.some((row) => row.id === "sep28" && row.type === "fall_alert") ? 3 : null,
      }
    : events
      ? {
          ...countsInWindow(events),
          average: averageResponseMinutes(events),
        }
      : { total: 0, done: 0, open: 0, average: null as number | null };

  return (
    <main className="screen history">
      <img className="history-house" src="/assets/house-calm.svg" alt="" />
      <h1 className="display page-kicker">기록</h1>
      <p className="lbl">최근 90일 알림</p>
      <p className="display big-count">
        {summary.total}
        <span>건</span>
      </p>
      <p className="summary-line">
        <b className="ok-text">✓ 확인함 {summary.done}</b>
        <span> · </span>
        <b className="warn-text">⚠ 확인 안 됨 {summary.open}</b>
        {summary.average !== null ? <span> · 평균 확인 {summary.average}분</span> : null}
      </p>
      <section className="chart-band">
        <div className="chart-head">
          <b>주별 알림 · 최근 12주</b>
          {mock ? <span className="sub">예시 데이터</span> : null}
        </div>
        {mock ? <MockChart /> : <LiveChart weeks={weekMarks(events ?? [])} />}
      </section>
      <div className="filters" role="tablist" aria-label="기록 필터">
        {(
          [
            ["all", `전체 ${summary.total}`],
            ["done", `확인함 ${summary.done}`],
            ["open", `확인 안 됨 ${summary.open}`],
          ] as const
        ).map(([key, label]) => (
          <button key={key} type="button" role="tab" aria-selected={filter === key} className={filter === key ? "on" : undefined} onClick={() => setFilter(key)}>
            {label}
          </button>
        ))}
      </div>
      <div className="history-list">
        {error ? <p className="warn-line">{error}</p> : null}
        {!mock && events === null && !error ? <p className="sub">기록을 불러오고 있어요</p> : null}
        {visible.length === 0 && (mock || events) ? <p className="sub empty-copy">이 목록에는 알림이 없어요</p> : null}
        {visible.map((row) => (
          <Link key={row.id} to={`/alert/${row.id}`} className="row history-row">
            <span className="when-col">
              <b>{row.date}</b>
              <span className="sub">{row.time}</span>
            </span>
            <span className="row-text">{row.text}</span>
            {row.type === "fall_alert" ? (
              <span className="badge warn">
                <IconAlert className="sm" />
                확인 안 됨
              </span>
            ) : row.type === "self_cancelled" ? (
              <span className="ok-text">본인 응답</span>
            ) : (
              <span className="ok-text">
                <IconCheck className="sm" /> 확인함
              </span>
            )}
          </Link>
        ))}
      </div>
      <TabBar />
    </main>
  );
}
