import { useEffect, useRef, useState } from "react";
import { useNavigate } from "react-router-dom";
import { enablePush, errorText, guardianApi } from "../api";
import { IconCheck } from "../icons";
import { useSession } from "../session";

const demoCode = ["4", "8", "2", "9", "1", "3"];

function standalone(): boolean {
  const nav = navigator as Navigator & { standalone?: boolean };
  return window.matchMedia("(display-mode: standalone)").matches || Boolean(nav.standalone);
}

export function ConnectScreen() {
  const navigate = useNavigate();
  const { mock, token, setToken, notif, setNotif, mockState, grantMockNotif } = useSession();
  const [digits, setDigits] = useState<string[]>(mock ? demoCode : ["", "", "", "", "", ""]);
  const [focus, setFocus] = useState(mock ? 5 : 0);
  const [verified, setVerified] = useState(mock);
  const [label, setLabel] = useState(mock ? "어머니 댁" : "");
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState("");
  const inputs = useRef<Array<HTMLInputElement | null>>([]);
  const stayOnConnect = useRef(false);
  const iosReady = mock || standalone();
  const notifReady = mock ? mockState.notifGranted : notif === "granted";
  const doneCount = Number(notifReady) + Number(iosReady);

  useEffect(() => {
    document.title = "보호자 연결 · 낙상제로";
    if (!mock && token && !stayOnConnect.current) navigate("/home", { replace: true });
  }, [mock, token, navigate]);

  async function verify(next: string[]) {
    const code = next.join("");
    if (!/^\d{6}$/.test(code)) {
      setVerified(false);
      setLabel("");
      return;
    }
    if (mock) {
      setVerified(true);
      setLabel("어머니 댁");
      setToken("mock");
      setMessage("");
      return;
    }
    setBusy(true);
    setMessage("");
    try {
      const paired = await guardianApi.pair(code);
      stayOnConnect.current = true;
      setToken(paired.guardianToken);
      const address = await guardianApi.address(paired.guardianToken);
      setLabel(address.detail);
      setVerified(true);
    } catch (error) {
      setVerified(false);
      setToken(null);
      setMessage(errorText(error));
    } finally {
      setBusy(false);
    }
  }

  function write(index: number, value: string, nextFocus = true) {
    const clean = value.replace(/\D/g, "");
    const next = [...digits];
    if (!clean) {
      next[index] = "";
      setDigits(next);
      setVerified(false);
      return;
    }
    let cursor = index;
    for (const char of clean.slice(0, 6 - index)) {
      next[cursor] = char;
      cursor += 1;
    }
    setDigits(next);
    const target = Math.min(cursor, 5);
    if (nextFocus) {
      setFocus(target);
      inputs.current[target]?.focus();
    }
    void verify(next);
  }

  async function allowNotifications() {
    setMessage("");
    if (mock) {
      grantMockNotif();
      return;
    }
    if (!token) {
      setMessage("먼저 여섯 자리 코드를 확인해 주세요.");
      return;
    }
    try {
      await enablePush(token);
      setNotif("granted");
    } catch (error) {
      if (error instanceof Error && error.message === "denied") {
        setNotif("denied");
        setMessage("알림이 허용되지 않았어요. 브라우저 설정에서 다시 켤 수 있어요.");
        return;
      }
      if (error instanceof Error && error.message === "unsupported") {
        setMessage("이 브라우저는 알림을 받지 못해요. 아이폰은 홈 화면에 추가한 뒤 허용해 주세요.");
        return;
      }
      setMessage(errorText(error));
    }
  }

  return (
    <main className="screen connect">
      <img className="phone-hand" src="/assets/phone-hand.svg" alt="" />
      <p className="wordmark">
        <span className="display">낙상제로</span> <span className="role">보호자</span>
      </p>
      <div className="connect-copy">
        <h1 className="display">
          어르신 폰과
          <br />
          연결해요
        </h1>
        <p className="sub">
          어르신 폰 첫 화면의
          <br />
          6자리 코드예요.
        </p>
      </div>
      <div className="code" role="group" aria-label="초대 코드 여섯 자리">
        {digits.map((digit, index) => (
          <input
            key={index}
            ref={(node) => {
              inputs.current[index] = node;
            }}
            className={`digit${index === focus ? " focus" : ""}${index === 3 ? " split" : ""}`}
            inputMode="numeric"
            autoComplete={index === 0 ? "one-time-code" : "off"}
            aria-label={`${index + 1}번째 숫자`}
            maxLength={1}
            value={digit}
            onFocus={() => setFocus(index)}
            onChange={(event) => write(index, event.target.value)}
            onKeyDown={(event) => {
              if (event.key === "Backspace" && !digits[index] && index > 0) {
                setFocus(index - 1);
                inputs.current[index - 1]?.focus();
              }
            }}
            onPaste={(event) => {
              event.preventDefault();
              write(index, event.clipboardData.getData("text"));
            }}
          />
        ))}
      </div>
      {verified ? (
        <p className="ok-line">
          <IconCheck className="sm" />
          코드 확인됨 · {label}
        </p>
      ) : (
        <p className="ok-line quiet" role="status">
          {busy ? "코드를 확인하고 있어요" : message || "숫자 여섯 자리를 입력해 주세요"}
        </p>
      )}
      <section className="permit" aria-labelledby="permit-title">
        <div className="permit-head">
          <h2 id="permit-title" className="display">
            꼭 허용해 주세요
          </h2>
          <span className="sub">
            {doneCount} / 2 완료
          </span>
        </div>
        <div className="row">
          <span className={`step${notifReady ? "" : " on"}`}>1</span>
          <div className="row-copy">
            <b>알림 허용</b>
            <div className="sub">넘어짐을 바로 알려 드릴 수 있어요</div>
          </div>
          {notifReady ? (
            <span className="badge ok">
              <IconCheck className="sm" />
              완료
            </span>
          ) : (
            <button type="button" className="btn tiny" onClick={() => void allowNotifications()}>
              허용
            </button>
          )}
        </div>
        <div className="row">
          <span className={`step${iosReady ? "" : " on"}`}>2</span>
          <div className="row-copy">
            <b>아이폰은 홈 화면에 추가</b>
            <div className="sub">공유 → ‘홈 화면에 추가’ 후 허용돼요</div>
            <div className="sub">① 공유 ② 홈 화면에 추가 ③ 알림 허용</div>
          </div>
          {iosReady ? (
            <span className="badge ok">
              <IconCheck className="sm" />
              완료
            </span>
          ) : (
            <span className="badge wait">안내</span>
          )}
        </div>
      </section>
      {message && verified ? (
        <p className="warn-line" role="status">
          {message}
        </p>
      ) : null}
      <div className="dock">
        <button
          type="button"
          className="btn"
          disabled={!verified || busy}
          onClick={() => {
            if (verified) navigate("/home");
          }}
        >
          연결하기
        </button>
      </div>
    </main>
  );
}
