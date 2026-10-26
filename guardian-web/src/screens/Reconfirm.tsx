import { useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import { errorText, guardianApi } from "../api";
import { addOneMonthLabel, formatKoreanDate } from "../format";
import { IconCalendar, IconCheck, IconPencil } from "../icons";
import { useSession } from "../session";
import type { Address } from "../types";

type Draft = {
  address: string;
  detail: string;
  entryMethod: string;
  roomNote: string;
};

function toDraft(address: Address): Draft {
  return {
    address: address.address,
    detail: address.detail,
    entryMethod: address.entryMethod ?? "",
    roomNote: address.roomNote ?? "",
  };
}

export function ReconfirmScreen() {
  const navigate = useNavigate();
  const { mock, token, mockState, confirmMock, updateMockAddress, setToken } = useSession();
  const [address, setAddress] = useState<Address | null>(mock ? mockState.address : null);
  const [confirmedLabel, setConfirmedLabel] = useState(mock ? mockState.confirmedLabel : "");
  const [editing, setEditing] = useState(false);
  const [draft, setDraft] = useState<Draft | null>(mock ? toDraft(mockState.address) : null);
  const [error, setError] = useState("");
  const [pending, setPending] = useState(false);
  const [saved, setSaved] = useState("");

  useEffect(() => {
    document.title = "주소 재확인 · 낙상제로";
    if (mock || !token) return;
    let stop = false;
    guardianApi
      .address(token)
      .then((next) => {
        if (stop) return;
        setAddress(next);
        setDraft(toDraft(next));
        setConfirmedLabel(next.addressConfirmedAt ? formatKoreanDate(next.addressConfirmedAt) : "아직 없음");
      })
      .catch((err) => {
        if (err instanceof Error && "status" in err && (err as { status: number }).status === 401) setToken(null);
        else if (!stop) setError(errorText(err));
      });
    return () => {
      stop = true;
    };
  }, [mock, token, setToken]);

  async function keep() {
    setPending(true);
    setError("");
    try {
      if (mock) {
        const label = formatKoreanDate(new Date().toISOString());
        const next = addOneMonthLabel(new Date().toISOString());
        confirmMock(label, next);
        setConfirmedLabel(label);
      } else if (token) {
        const next = await guardianApi.confirmAddress(token);
        setAddress(next);
        setConfirmedLabel(formatKoreanDate(next.addressConfirmedAt!));
      }
      setSaved("그대로 확인했어요");
    } catch (err) {
      setError(errorText(err));
    } finally {
      setPending(false);
    }
  }

  async function save() {
    if (!draft || !draft.address.trim() || !draft.detail.trim()) {
      setError("도로명 주소와 동·호수를 적어 주세요.");
      return;
    }
    setPending(true);
    setError("");
    const body = {
      address: draft.address.trim(),
      detail: draft.detail.trim(),
      entryMethod: draft.entryMethod.trim() || null,
      roomNote: draft.roomNote.trim() || null,
    };
    try {
      if (mock) {
        const next = { ...body, addressConfirmedAt: new Date().toISOString() };
        const label = formatKoreanDate(next.addressConfirmedAt);
        updateMockAddress(next);
        confirmMock(label, addOneMonthLabel(next.addressConfirmedAt));
        setAddress(next);
        setConfirmedLabel(label);
      } else if (token) {
        const next = await guardianApi.updateAddress(token, body);
        setAddress(next);
        setConfirmedLabel(next.addressConfirmedAt ? formatKoreanDate(next.addressConfirmedAt) : confirmedLabel);
      }
      setEditing(false);
      setSaved("주소를 고치고 확인했어요");
    } catch (err) {
      setError(errorText(err));
    } finally {
      setPending(false);
    }
  }

  return (
    <main className="screen reconfirm">
      <div className="town-hero">
        <img src="/assets/town.svg" alt="" />
        <button type="button" className="back-chip" onClick={() => navigate("/home")}>
          <span className="i-wrap" aria-hidden="true">
            <svg viewBox="0 0 24 24">
              <path d="M15 5l-7 7 7 7" />
            </svg>
          </span>
          주소 재확인
        </button>
      </div>
      <div className="reconfirm-body">
        <p className="warn-line plain">
          <IconCalendar className="sm" />
          한 달에 한 번 확인해요
        </p>
        <p className="sub">위급할 때 정확히 찾아가기 위해서예요.</p>
        <h1 className="display">
          주소가 그대로인지
          <br />
          확인해 주세요
        </h1>
        {editing && draft ? (
          <form
            className="edit-form"
            onSubmit={(event) => {
              event.preventDefault();
              void save();
            }}
          >
            <label>
              도로명 주소
              <input value={draft.address} onChange={(event) => setDraft({ ...draft, address: event.target.value })} />
            </label>
            <label>
              동·호수
              <input value={draft.detail} onChange={(event) => setDraft({ ...draft, detail: event.target.value })} />
            </label>
            <label>
              공동현관
              <input value={draft.entryMethod} onChange={(event) => setDraft({ ...draft, entryMethod: event.target.value })} />
            </label>
            <label>
              폰 둔 방
              <input value={draft.roomNote} onChange={(event) => setDraft({ ...draft, roomNote: event.target.value })} />
            </label>
          </form>
        ) : (
          <>
            <div className="address-quote">
              <div className="street">{address?.address ?? "주소를 불러오고 있어요"}</div>
              <div className="unit">{address?.detail}</div>
            </div>
            <div className="facts">
              <div className="row">
                <span className="k">공동현관</span>
                <span className="v">{address?.entryMethod || "없음"}</span>
              </div>
              <div className="row">
                <span className="k">폰 둔 방</span>
                <span className="v">{address?.roomNote || "없음"}</span>
              </div>
              <div className="row">
                <span className="k">마지막 확인</span>
                <span className="v">
                  {confirmedLabel || "아직"} {mock ? <span className="sub">· 예시 데이터</span> : null}
                </span>
              </div>
            </div>
          </>
        )}
        {saved ? (
          <p className="ok-line" role="status">
            <IconCheck className="sm" />
            {saved}
          </p>
        ) : null}
        {error ? <p className="warn-line">{error}</p> : null}
      </div>
      <div className="dock split">
        {editing ? (
          <>
            <button type="button" className="btn ghost" onClick={() => setEditing(false)}>
              취소
            </button>
            <button type="button" className="btn" disabled={pending} onClick={() => void save()}>
              저장
            </button>
          </>
        ) : (
          <>
            <button
              type="button"
              className="btn ghost"
              onClick={() => {
                if (address) setDraft(toDraft(address));
                setEditing(true);
                setSaved("");
              }}
            >
              <IconPencil className="sm" />
              수정할게요
            </button>
            <button type="button" className="btn grow" disabled={pending || !address} onClick={() => void keep()}>
              <IconCheck className="sm" />
              그대로예요
            </button>
          </>
        )}
      </div>
    </main>
  );
}
