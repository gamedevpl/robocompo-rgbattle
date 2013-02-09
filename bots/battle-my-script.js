var target = null;
var middle = null;

// Rozpoznaj polecenie od gracza, namierz cel i ustaw go dla całej drużyny
if (status.input && status.input.match(/^target-([0-9]+)$/) && (t - status.inputTime) < 30000)
	teamMemory.target = memory.target = parseInt(status.input.match(/^target-([0-9]+)$/)[1]);

var antiff = new Array(); // struktura, w której oznaczam te sektory w polu
// widzenia, w
// których znajdują się sojusznicze jednostki
// w pewnym sensie jest to również zbuffer, chcemy strzelać tylko do tych celów,
// pomiędzy którymi a nami nie ma sojuszników

if (!teamMemory.checkFF) // funkcja sprawdzająca czy wskazany sektor pola
	// widzenia nie jest przysłonięty przez sojuszniczą
	// jednostkę
	teamMemory.checkFF = function(a, d, _antiff) {
		for ( var i = 0; i < _antiff.length; i++)
			if (_antiff[i][0] <= a && _antiff[i][1] >= a && (d == 0 || _antiff[i][2] < d))
				return true;
		return false;
	};

// Szukaj sojuszniczych jednostek w polu widzenia i oznacz je w strukturze
// antiff
for ( var i = 0; i < viewport.length; i++) {
	var object = viewport[i];
	if (object[4] == TYPE_ALLY || object[4] == TYPE_TEAM) {
		// jeśli jest to sojusznicza jednostka, oznacz sektor o szerokości(kąt w
		// radianach)
		// wyliczonej na podstawie arcusa tangensa szerokości jednostki(8) i
		// dystansu(object[2]).
		var at = Math.atan2(8, object[2]);
		antiff.push([object[1] - at, object[1] + at, object[2]]);
	}
}

// struktura służąca do wykluczania celów z analizy na jakiś czas
// identyfikatorom celów są przyporządkowane czasy, po których zostaną usunięte
// ze struktury i znów będą rozpatrywane podczas analizy pola widzenia
if (!memory.targetTimeout)
	memory.targetTimeout = {};
else {
	// wyczyść przeterminowane wykluczenia
	for ( var i in memory.targetTimeout)
		if (memory.targetTimeout[i] > t)
			memory.targetTimeout[i] = null;
}

// jeśli jednostka nie ma celu, pobierz cel drużyny(jeśli taki jest)
// o ile nie został on wykluczony
if (!memory.target && teamMemory.target && !memory.targetTimeout[teamMemory.target])
	memory.target = teamMemory.target;

for ( var i = 0; i < viewport.length; i++) {
	var object = viewport[i];
	if (object[4] == TYPE_ENEMY) {
		if (object[0] == memory.target) { // poprzedni cel, dalej strzelaj
			// do
			// niego
			target = object;
			break;
		}

		// zezwoł na przysłonięty cel jeśli nie jest on odległy
		if (memory.targetTimeout[object[0]] || object[2] > 200 && teamMemory.checkFF(object[1], object[2], antiff))
			continue;

		var score = object[2] * Math.abs(object[1]); // oceniaj cele na
		// podstawie
		// odleglosci i kąta, im niższy
		// wynik tym lepiej
		if (!target || score < target.score) {
			target = object;
			target.score = score;
		}
	}

	// zapisz pozycję środka mapy
	if (object[4] == TYPE_CENTER)
		middle = object;
}

if (!target)
	memory.target = null;

if (target) { // jeśli jest cel, strzelaj do niego
	// Nie jest to implementacja poprawna pod względem matematycznym, ale
	// przynosi porządany efekt. :)

	memory.target = target[0];

	if (!teamMemory.target)
		teamMemory.target = memory.target;

	if (target[1] < -0.01) {
		setState(STATE_ROTATE_RIGHT, true);
	} else if (target[1] > 0.01) {
		setState(STATE_ROTATE_LEFT, true);
	} else {
		setState(STATE_ROTATE_RIGHT | STATE_ROTATE_LEFT, false);
	}

	setState(STATE_ROTATE_FAST, Math.abs(target[1]) > 0.3);

	var ff = teamMemory.checkFF(object[1], object[2], antiff);
	if (ff && (memory.ffTimeout == null)) {
		// jeśli nasz cel jest przysłonięty przez dłużej niż 5 sekund, poszukaj
		// innego
		// W tym celu zapisujemy w pamięci aktualny czas
		memory.ffTimeout = true;
		memory.timeOut = t;
	} else if (ff && (memory.ffTimeout == true) && (t - memory.timeOut) > 5000) {
		// wyklucz cel na 5 sekund i poszukaj innego w następnym przebiegu
		memory.targetTimeout[memory.target] = t + 5000;
		memory.target = null;
		memory.ffTimeout = null;
	}

	// strzelaj jeśli cel jest namierzony i nie przesłania go żaden sojusznik
	// idź w kierunku celu jeśli jest daleko
	setState(STATE_SHOOT, Math.abs(target[1]) < 0.1 && !ff);
	setState(STATE_MOVE, (Math.abs(target[1]) < 0.1 || ff) && target[2] > 200);
} else if (middle != null) { // w przeciwnym wypadku idź w kierunku środka
	// nie strzelaj
	setState(STATE_SHOOT, false);
	// mapy
	if (middle[1] < -0.1)
		setState(STATE_ROTATE_RIGHT, true);
	else if (middle[1] > 0.1)
		setState(STATE_ROTATE_LEFT, true);
	else
		setState(STATE_ROTATE_RIGHT | STATE_ROTATE_LEFT, false);

	setState(STATE_ROTATE_FAST, Math.abs(middle[1]) > 0.1);

	setState(STATE_MOVE, middle[2] > 300 && Math.abs(middle[1]) < 0.2);
}

// Rozpoznaj polecenie i udaj się w kierunku wskaźnika
if (status.input == 'pointer' && (t - status.inputTime) < 30000) {
	setState(STATE_SHOOT, false);
	target = viewport[1];
	setState(STATE_MOVE, target[2] > 50);
	setState(STATE_ROTATE_FAST, Math.abs(target[1]) > 0.1);
	if (target[1] < -0.001) {
		setState(STATE_ROTATE_RIGHT, true);
	} else if (target[1] > 0.001) {
		setState(STATE_ROTATE_LEFT, true);
	} else {
		setState(STATE_ROTATE_RIGHT | STATE_ROTATE_LEFT, false);
	}
}