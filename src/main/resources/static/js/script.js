function showMessage(text, type = "info") {
  const area = document.getElementById("messageArea");
  area.innerHTML = `<div class="message ${type}">${text}</div>`;
  area.style.display = "block";
  setTimeout(() => {
    area.style.display = "none";
  }, 3000);
}

function setLoading(btn, label) {
  if (!btn) return () => {};
  const prev = btn.textContent;
  btn.textContent = label;
  btn.disabled = true;
  btn.classList.add("loading");
  return () => {
    btn.textContent = prev;
    btn.disabled = false;
    btn.classList.remove("loading");
  };
}

function fetchWithTimeout(url, options = {}, timeoutMs = 8000) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  const opts = {
    ...options,
    signal: controller.signal,
    headers: {
      "Content-Type": "application/json",
      ...(options.headers || {}),
    },
  };
  return fetch(url, opts).finally(() => clearTimeout(timer));
}

async function search(btn) {
  const keyword = document.getElementById("searchInput").value.trim();
  if (!keyword) return;
  const done = setLoading(btn, "검색 중...");
  try {
    const res = await fetchWithTimeout("/api/search", {
      method: "POST",
      body: JSON.stringify({ keyword }),
    });
    if (!res.ok) throw new Error();

    const data = await res.json();

    // Redis Key:Value 데이터를 콘솔에 출력 (F12에서 확인 가능)
    console.group("🔥 Redis Key:Value 데이터");
    console.log("Key: popular_keywords");
    console.log("Value (Sorted Set):", data.redisKeys.popular_keywords);
    console.log("Key: recent_keywords");
    console.log("Value (List):", data.redisKeys.recent_keywords);
    console.groupEnd();

    document.getElementById("searchInput").value = "";
    addUserSearchKeyword(keyword);
    await updatePopularKeywords();
    showMessage(`"${keyword}" 검색이 완료되었습니다! (F12로 Redis 데이터 확인)`, "success");
  } catch {
    showMessage("검색 중 오류가 발생했습니다.", "error");
  } finally {
    done();
  }
}

function addUserSearchKeyword(keyword) {
  const el = document.getElementById("recentKeywords");
  const cur = el.innerHTML;
  const newHtml = `<div class="keyword-item" style="color:#007bff;font-weight:bold;background:#e3f2fd;border:2px solid #007bff;">🔍 ${keyword}</div>`;
  el.innerHTML = newHtml + cur;
  const items = el.querySelectorAll(".keyword-item");
  if (items.length > 15) items[items.length - 1].remove();
}

async function updatePopularKeywords() {
  try {
    const r = await fetchWithTimeout("/api/search/popular");
    if (r.ok) {
      const data = await r.json();

      // Redis Key:Value 정보를 콘솔에 출력
      console.group("📊 인기 검색어 Redis 데이터");
      console.log(`Key: ${data.redisKey}`);
      console.log("Value:", data.redisValue);
      console.log("총 개수:", data.totalCount);
      console.groupEnd();

      displayKeywords("popularKeywords", Array.isArray(data.keywords) ? data.keywords : []);
    }
  } catch {}
}

async function loadKeywords() {
  try {
    const [p, r] = await Promise.all([
      fetchWithTimeout("/api/search/popular"),
      fetchWithTimeout("/api/search/recent"),
    ]);

    if (p.ok) {
      const popularData = await p.json();
      console.group("🔥 초기 로딩 - 인기 검색어 Redis");
      console.log(`Key: ${popularData.redisKey}`);
      console.log("Value:", popularData.redisValue);
      console.groupEnd();
      displayKeywords("popularKeywords", Array.isArray(popularData.keywords) ? popularData.keywords : []);
    }

    if (r.ok) {
      const recentData = await r.json();
      console.group("📝 초기 로딩 - 최근 검색어 Redis");
      console.log(`Key: ${recentData.redisKey}`);
      console.log("Value:", recentData.redisValue);
      console.groupEnd();
      displayKeywords("recentKeywords", Array.isArray(recentData.keywords) ? recentData.keywords : []);
    }
  } catch {
    displayKeywords("popularKeywords", []);
    displayKeywords("recentKeywords", []);
  }
}

function displayKeywords(id, list) {
  const el = document.getElementById(id);
  if (!Array.isArray(list) || list.length === 0) {
    el.innerHTML = '<div class="keyword-item">검색어가 없습니다</div>';
    return;
  }
  el.innerHTML = list
    .map((kw, i) => {
      let s = "";
      if (i === 0) s = ' style="color:#e74c3c;font-weight:bold;"';
      else if (i === 1) s = ' style="color:#f39c12;font-weight:bold;"';
      else if (i === 2) s = ' style="color:#f1c40f;font-weight:bold;"';
      return `<div class="keyword-item"${s}>${i + 1}. ${kw}</div>`;
    })
    .join("");
}

document.getElementById("searchInput").addEventListener("keypress", (e) => {
  if (e.key === "Enter") search(null);
});

function pickValue(x) {
  if (x == null) return "";
  if (typeof x === "string") return x;
  if (typeof x.value === "string") return x.value;
  if (typeof x.member === "string") return x.member;
  if (typeof x.element === "string") return x.element;
  return String(x.value ?? x.member ?? x.element ?? x);
}

function pickScore(x) {
  if (x == null) return "";
  if (typeof x.score === "number" || typeof x.score === "string")
    return x.score;
  return "";
}

async function generateTestData(btn) {
  const done = setLoading(btn, "데이터 생성 중...");
  try {
    const r = await fetchWithTimeout("/api/test/generate-data", {
      method: "POST",
    });
    if (!r.ok) throw new Error();
    await r.json();
    await loadKeywords();
    showMessage("테스트 데이터가 성공적으로 생성되었습니다!", "success");
  } catch {
    showMessage("테스트 데이터 생성 중 오류가 발생했습니다.", "error");
  } finally {
    done();
  }
}

async function clearCache(btn) {
  const done = setLoading(btn, "초기화 중...");
  try {
    const r = await fetchWithTimeout("/api/test/clear-cache", {
      method: "POST",
    });
    if (!r.ok) throw new Error();
    await r.json();
    await loadKeywords();
    showMessage("캐시가 성공적으로 초기화되었습니다!", "info");
  } catch {
    showMessage("캐시 초기화 중 오류가 발생했습니다.", "error");
  } finally {
    done();
  }
}

async function checkRedisStatus(btn) {
  const done = setLoading(btn, "확인 중...");
  try {
    const r = await fetchWithTimeout("/api/search/debug/redis-status");
    if (!r.ok) throw new Error();
    const status = await r.json();

    // Redis 전체 상태를 콘솔에 출력
    console.group("🔍 Redis 전체 상태 확인");
    console.log("Redis Data Structure:", status.redisData);
    console.log("Raw Status:", status);
    console.groupEnd();

    const el = document.getElementById("performanceComparison");
    const pop = Array.isArray(status.popularKeywords) ? status.popularKeywords : [];
    const rec = Array.isArray(status.recentKeywords) ? status.recentKeywords : [];

    const popHtml = pop
      .map(
        (it) =>
          `<div class="keyword-item">${pickValue(it)}${
            pickScore(it) !== "" ? ` (${pickScore(it)}점)` : ``
          }</div>`
      )
      .join("");
    const recHtml = rec
      .map(
        (it, i) => `<div class="keyword-item">${i + 1}. ${pickValue(it)}</div>`
      )
      .join("");

    el.innerHTML = `
      <div class="keyword-item" style="font-weight:bold;color:#007bff;">Redis Key:Value 상태 정보</div>
      <div class="keyword-item">Key: "popular_keywords" (SortedSet) - ${status.totalPopularCount || 0}개</div>
      <div class="keyword-item">Key: "recent_keywords" (List) - ${status.totalRecentCount || 0}개</div>
      <div class="keyword-item" style="margin-top:10px;font-weight:bold;">popular_keywords Value (점수 포함):</div>
      ${popHtml || '<div class="keyword-item">데이터가 없습니다</div>'}
      <div class="keyword-item" style="margin-top:10px;font-weight:bold;">recent_keywords Value:</div>
      ${recHtml || '<div class="keyword-item">데이터가 없습니다</div>'}`;
    showMessage("Redis 상태를 확인했습니다. F12 Console에서 상세 정보 확인!", "info");
  } catch {
    showMessage("Redis 상태 확인 중 오류가 발생했습니다.", "error");
  } finally {
    done();
  }
}

async function compareRedisVsDB(btn) {
  const done = setLoading(btn, "비교 중...");
  try {
    const r = await fetchWithTimeout("/api/search/compare/redis-vs-db");
    if (!r.ok) throw new Error();
    const c = await r.json();

    // Redis Key:Value 데이터와 성능 비교 결과를 콘솔에 출력
    console.group("⚡ Redis vs DB 성능 비교 + Key:Value 데이터");
    console.log("Redis 조회 시간:", c.redisTime);
    console.log("DB 조회 시간:", c.dbTime);
    console.log("성능 향상:", c.performanceImprovement);
    console.log("Redis Key:Value 데이터:", c.redisKeyValueData);
    console.groupEnd();

    const el = document.getElementById("performanceComparison");
    const r1 = Array.isArray(c.redisResult) ? c.redisResult : [];
    const r2 = Array.isArray(c.dbResult) ? c.dbResult : [];

    el.innerHTML = `
      <div class="keyword-item" style="font-weight:bold;color:#007bff;">Redis vs DB 성능 비교 결과</div>
      <div class="keyword-item">Redis 조회 시간: ${c.redisTime}</div>
      <div class="keyword-item">DB 조회 시간: ${c.dbTime}</div>
      <div class="keyword-item" style="color:#28a745;">성능 향상: ${c.performanceImprovement}</div>
      <div class="keyword-item" style="margin-top:10px;font-weight:bold;">Redis Key:Value에서 조회한 결과:</div>
      ${r1.map((x, i) => `<div class="keyword-item">${i + 1}. ${x}</div>`).join("")}
      <div class="keyword-item" style="margin-top:10px;font-weight:bold;">DB에서 조회한 결과:</div>
      ${r2.map((x, i) => `<div class="keyword-item">${i + 1}. ${x}</div>`).join("")}
      <div class="keyword-item" style="margin-top:15px;color:#dc3545;font-weight:bold;">
        🔍 F12 Console에서 Redis Key:Value 원본 데이터 확인 가능!
      </div>
    `;
    showMessage("성능 비교가 완료되었습니다! F12에서 Redis 데이터 확인!", "success");
  } catch {
    showMessage("성능 비교 중 오류가 발생했습니다.", "error");
  } finally {
    done();
  }
}

// Redis 키 정보 조회 함수 추가
async function showRedisKeys() {
  try {
    const r = await fetchWithTimeout("/api/search/debug/redis-keys");
    if (!r.ok) throw new Error();
    const data = await r.json();

    console.group("Redis Keys 상세 정보");
    console.log("모든 Redis Keys:", data.keys);
    console.groupEnd();

    showMessage("Redis Keys 정보가 F12 Console에 출력되었습니다!", "info");
  } catch {
    showMessage("Redis Keys 조회 중 오류가 발생했습니다.", "error");
  }
}

// 전역 함수로 등록 (콘솔에서 직접 호출 가능)
window.showRedisKeys = showRedisKeys;

(async function init() {
  await loadKeywords();
  setInterval(updatePopularKeywords, 3000);

  // 초기 로딩 시 Redis 정보 안내
  console.log("실시간 검색어 시스템 시작!");
  console.log("F12 Console에서 showRedisKeys() 함수로 Redis 상세 정보 확인 가능");
})();

Object.assign(window, {
  search,
  generateTestData,
  clearCache,
  checkRedisStatus,
  compareRedisVsDB,
  showRedisKeys,
});