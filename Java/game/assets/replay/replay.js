"use strict";
const $ = id => document.getElementById(id);
const token = location.pathname.split("/")[1];
let state;
let busy = false;
const escape = value => String(value).replace(/[&<>"']/g, char => ({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;","'":"&#39;"}[char]));

async function request(path, body) {
  const result = await fetch(path, body === undefined ? {} : {
    method:"POST", headers:{"Content-Type":"application/json", "X-Replay-Token":token}, body:JSON.stringify(body)
  });
  if (!result.ok) throw new Error(await result.text());
  return result.json();
}

function scale(name, question, labels) {
  return `<fieldset><legend>${question}</legend><div class="choices">${labels.map((label,i) =>
    `<label class="choice"><input type="radio" name="${name}" value="${i+1}" required><strong>${i+1}</strong><span>${label}</span></label>`).join("")}</div></fieldset>`;
}
function choices(name, question, options) {
  return `<fieldset><legend>${question}</legend><div class="stack">${options.map(([value,label]) =>
    `<label><input type="radio" name="${name}" value="${value}" required>${label}</label>`).join("")}</div></fieldset>`;
}
function showError(error) {
  $("notice").textContent = error.message || "Keine Verbindung zum lokalen Replay. Bitte replay-open.html erneut öffnen.";
}
async function action(work) {
  if (busy) return;
  busy = true;
  $("notice").textContent = "";
  document.querySelectorAll("button").forEach(button => button.disabled = true);
  try { await work(); render(); } catch(error) {
    showError(error);
    document.querySelectorAll("button").forEach(button => button.disabled = false);
  } finally { busy = false; }
}

function render() {
  $("study").textContent = "Studien-ID " + state.studyId;
  if (state.error) { $("title").textContent = "Der Rückblick braucht Unterstützung."; $("notice").textContent = state.error; return; }
  if (!state.ready) return;
  const answers = state.responses.answers;
  let index = answers.length;
  if (index && state.clips[index-1].intervention && !answers[index-1].intervention) index--;
  if (state.submitted) { completed(); return; }
  if (index === state.clips.length) { finish(); return; }
  const clip = state.clips[index];
  const after = index < answers.length;
  $("progress").textContent = `Ausschnitt ${index+1} von ${state.clips.length} · ${after ? "Fortsetzung" : "Deine Einschätzung"}`;
  $("title").textContent = after ? "Wie hast du die Unterstützung erlebt?" : "Wie ging es dir in diesem Moment?";
  $("intro").textContent = after
    ? "Sieh dir jetzt die Fortsetzung an. Bewerte die Unterstützung nur, wenn sie für dich im Ausschnitt erkennbar ist."
    : "Sieh dir den Ausschnitt an. Erinnere dich daran, wie du dich damals im Spiel gefühlt hast. Bewerte dieses Gefühl, nicht dein Gefühl beim jetzigen Anschauen. Beantworte die Fragen allein.";
  const anchors = ["Not at all", "Very little", "Moderate", "Strong", "Very strong"];
  let fields;
  if (!after) {
    const reactions = [["NONE","Nichts"],["HINT","Einen Hinweis anbieten"]];
    if (clip.expandedSupport) reactions.push(["SIMPLIFY","Die Aufgabe vereinfachen"],["AUTO_COMPLETE","Die Aufgabe automatisch abschließen"]);
    fields = scale("frustrated","Frustrated",anchors) + scale("irritated","Irritated",anchors)
      + scale("dissatisfied","Dissatisfied",anchors)
      + scale("supportNeed","Wie dringend brauchte euer Team in dieser Situation Unterstützung?",["Überhaupt nicht","","","","Sehr dringend"])
      + choices("desiredReaction","Was hätte das System in dieser Situation tun sollen?",reactions);
  } else {
    fields = choices("visible","Kannst du die Unterstützung im Ausschnitt beurteilen?",[["yes","Ja"],["no","Nein, nicht erkennbar oder nicht beurteilbar"]])
      + `<div id="intervention-fields" hidden>`
      + choices("timing","Kam die Unterstützung zum richtigen Zeitpunkt?",[["EARLY","Zu früh"],["APPROPRIATE","Passend"],["LATE","Zu spät"]])
      + choices("intensity","War die Stärke der Unterstützung angemessen?",[["WEAK","Zu schwach"],["APPROPRIATE","Passend"],["STRONG","Zu stark"]])
      + scale("fit","Wie gut passte die Unterstützung zur damaligen Situation?",["Gar nicht","","","","Sehr gut"])
      + scale("helpfulness","Wie stark half die Unterstützung eurem Team beim Weiterkommen?",["Gar nicht","","","","Sehr stark"]) + `</div>`;
  }
  $("content").innerHTML = `<div class="workspace"><div class="playback"><video id="video" controls preload="metadata" playsinline src="${clip.id}-${after?"after":"before"}.mp4"></video>
    <p class="caption">Deine Perspektive · ${clip.id} · ${after?"Fortsetzung":"Situation"}</p>
    <p id="watch-status" class="watch-status">Starte das Video mit der Wiedergabetaste.</p>
    <p class="explanation">Du kannst diesen Ausschnitt wiederholen. Deine Bewertungen werden nach dem Weitergehen lokal gespeichert und nicht mehr verändert.</p></div>
    <form id="questions">${fields}<div class="actions"><button id="next" type="submit" disabled>Bewertungen speichern und weiter</button></div></form></div>`;
  $("video").addEventListener("ended", () => { $("next").disabled = false; $("watch-status").textContent = "Ausschnitt angesehen. Du kannst jetzt weitergehen."; });
  $("video").addEventListener("error", () => showError(new Error("Das Video lässt sich nicht abspielen. Bitte die Versuchsleitung informieren; keine Bewertungen raten.")));
  if (after) {
    const update = () => {
      const visible = new FormData($("questions")).get("visible") === "yes";
      $("intervention-fields").hidden = !visible;
      $("intervention-fields").querySelectorAll("input").forEach(input => input.disabled = !visible);
    };
    $("questions").addEventListener("change",update); update();
  }
  $("questions").addEventListener("submit", event => {
    event.preventDefault();
    const data = new FormData(event.currentTarget);
    const next = structuredClone(state.responses);
    if (after) {
      const visible = data.get("visible") === "yes";
      next.answers[index].intervention = {visible, timing:visible?data.get("timing"):null,
        intensity:visible?data.get("intensity"):null, fit:visible?Number(data.get("fit")):null,
        helpfulness:visible?Number(data.get("helpfulness")):null};
    } else {
      next.answers.push({clipId:clip.id, baseline:{frustrated:Number(data.get("frustrated")),
        irritated:Number(data.get("irritated")),dissatisfied:Number(data.get("dissatisfied")),
        supportNeed:Number(data.get("supportNeed")), desiredReaction:data.get("desiredReaction")}, intervention:null});
    }
    action(async () => { state = await request("save",next); });
  });
}

function finish() {
  $("progress").textContent = "Alle Ausschnitte bewertet";
  $("title").textContent = "Was möchtest du noch ergänzen?";
  $("intro").textContent = "Die beiden letzten Fragen sind freiwillig. Bitte nenne keine Namen oder anderen persönlichen Angaben. Anschließend werden deine Antworten an die Studien-Datenbank übertragen.";
  $("content").innerHTML = `<form id="finish" class="finish"><label for="frustration">Was hat dich im Spiel besonders frustriert?</label>
    <textarea id="frustration" maxlength="2000">${escape(state.responses.frustrationComment)}</textarea>
    <label for="support">Was würdest du an der automatischen Unterstützung ändern?</label>
    <textarea id="support" maxlength="2000">${escape(state.responses.supportComment)}</textarea>
    <button type="submit">Antworten abschicken</button><p class="explanation">Bei einem Verbindungsfehler bleiben deine Antworten hier gespeichert. Du kannst die Übertragung erneut versuchen.</p></form>`;
  $("finish").addEventListener("submit", event => {
    event.preventDefault();
    const next = {...state.responses,frustrationComment:$("frustration").value,supportComment:$("support").value};
    action(async () => { state = await request("save",next); state = await request("submit",{}); });
  });
}

function completed() {
  $("progress").textContent = "Befragung abgeschlossen";
  $("title").textContent = state.deleted ? "Die lokalen Aufnahmen sind gelöscht." : "Deine Antworten sind angekommen.";
  $("intro").textContent = state.deleted
    ? "Das Spielvideo und die vorbereiteten Ausschnitte wurden von diesem Computer entfernt. Die Antworten und die Empfangsbestätigung bleiben erhalten."
    : "Die Datenbank hat den Empfang bestätigt. Deine Videos liegen weiterhin nur auf diesem Computer. Informiere jetzt bitte die Versuchsleitung.";
  $("content").innerHTML = state.deleted ? `<p>Du kannst dieses Browserfenster schließen.</p>` : `<div class="finish"><p>Du kannst dieses Fenster offen lassen oder später über replay-open.html wieder öffnen.</p>
    <details><summary>Versuchsleitung: lokale Aufnahmen löschen</summary><p>Erst nach Prüfung der Ergebnisse freigeben. Diese Aktion löscht die lokale Originalaufnahme und die vorbereiteten Ausschnitte dauerhaft. Die Antworten in der Datenbank werden nicht gelöscht.</p>
    <form id="deletion"><label for="code">Freigabecode für diese Aufnahme</label><input type="password" id="code" autocomplete="off" required>
    <div class="stack"><label><input type="checkbox" required>Ich gebe die endgültige Löschung dieser lokalen Aufnahmen frei.</label></div>
    <button type="submit" class="secondary">Aufnahmen endgültig löschen</button></form></details></div>`;
  if (!state.deleted) $("deletion").addEventListener("submit", event => {
    event.preventDefault(); const code = $("code").value;
    action(async () => { state = await request("delete",{code}); });
  });
  const close = document.createElement("button");
  close.type = "button";
  close.className = "secondary";
  close.textContent = "Befragung beenden";
  $("content").append(close);
  close.addEventListener("click", async () => {
    close.disabled = true;
    try {
      await request("close",{});
      $("content").textContent = "Der lokale Replaydienst ist beendet. Du kannst dieses Fenster schließen. Deine gespeicherten Daten bleiben erhalten.";
    } catch(error) { showError(error); close.disabled = false; }
  });
}

async function start() {
  try {
    state = await request("state"); render();
    if (!state.ready && !state.error) setTimeout(start,1500);
  } catch(error) { showError(error); }
}
start();
