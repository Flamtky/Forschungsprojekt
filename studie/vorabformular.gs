// 1. Auf script.google.com ein neues Projekt öffnen und dieses Skript einfügen.
// 2. CONSENT_TEXT durch den unveränderten Einwilligungstext der Hochschule ersetzen.
// 3. createPreForm einmal ausführen, Berechtigungen bestätigen und die protokollierten Links verwenden.
const CONSENT_TEXT = 'HIER DEN EINWILLIGUNGSTEXT DER HOCHSCHULE UNVERÄNDERT EINFÜGEN';

function createPreForm() {
  if (!CONSENT_TEXT.trim() || CONSENT_TEXT.includes('HIER DEN EINWILLIGUNGSTEXT DER HOCHSCHULE UNVERÄNDERT EINFÜGEN')) {
    throw new Error('Bitte zuerst CONSENT_TEXT durch den unveränderten Einwilligungstext der Hochschule ersetzen. Es wurde kein Formular angelegt.');
  }

  const form = FormApp.create('The Last Hour: Vorabfragen');
  form.setCollectEmail(false)
    .setAllowResponseEdits(false)
    .setShowLinkToRespondAgain(false)
    .setConfirmationMessage('Danke! Du kannst jetzt mit dem Spiel beginnen.');
  try {
    form.setRequireLogin(false);
  } catch (error) {
    // Diese Einstellung ist nur für Workspace-Konten verfügbar.
    Logger.log('Anmeldeeinstellung nicht verfügbar. Vor dem Einsatz prüfen, dass keine Anmeldung verlangt wird.');
  }

  form.addSectionHeaderItem()
    .setTitle('Hinweis zu Spieleingaben')
    .setHelpText('Das Spiel protokolliert auch falsche Eingaben. Gib in die Spielfelder ausschließlich Lösungen oder Lösungsversuche für den Raum ein und verwende dort keine eigenen Namen, Passwörter oder sonstigen persönlichen Angaben.');
  form.addCheckboxItem()
    .setTitle('Mindestalter')
    .setChoiceValues(['Ich bestätige, dass ich mindestens 18 Jahre alt bin.'])
    .setRequired(true);
  form.addSectionHeaderItem()
    .setTitle('Einwilligung')
    .setHelpText(CONSENT_TEXT);
  form.addCheckboxItem()
    .setTitle('Einwilligung bestätigen')
    .setChoiceValues(['Ich willige ein.'])
    .setRequired(true);
  const idItem = form.addTextItem()
    .setTitle('Bitte gib die pseudonyme Kennung ein, die du vor dem Spiel erhalten hast.')
    .setHelpText('Format: P und drei Ziffern, z. B. P001')
    .setValidation(FormApp.createTextValidation()
      .requireTextMatchesPattern('^P[0-9]{3}$')
      .setHelpText('Format: P und drei Ziffern, z. B. P001')
      .build())
    .setRequired(true);
  form.addMultipleChoiceItem()
    .setTitle('Ich habe den Raum The Last Hour bereits gespielt.')
    .setChoiceValues(['Ja', 'Nein'])
    .setRequired(true);
  form.addMultipleChoiceItem()
    .setTitle('Wie oft hast du schon an einem Escape Room teilgenommen (vor Ort oder digital)?')
    .setChoiceValues(['noch nie', '1 bis 2 Mal', '3 bis 5 Mal', 'mehr als 5 Mal'])
    .setRequired(true);
  form.addMultipleChoiceItem()
    .setTitle('Wie oft spielst du Videospiele?')
    .setChoiceValues(['nie', 'seltener als einmal im Monat', 'mindestens einmal im Monat', 'mindestens einmal pro Woche', 'fast täglich'])
    .setRequired(true);

  Logger.log('Bearbeitungslink: ' + form.getEditUrl());
  Logger.log('Öffentlicher Link: ' + form.getPublishedUrl());
  Logger.log('Vorausgefüllter Link: ' + form.createResponse()
    .withItemResponse(idItem.createResponse('P000')).toPrefilledUrl());
  Logger.log('Diesen vorausgefüllten Link in pilot-data\\form-prefill.txt speichern.');
}
