/* ==========================================================================
   DataRail circuit field
   Injects the fixed edge-rail layer and drives colour and route switching.

   Pair with assets/circuit.css. A page needs nothing but the two includes:

     <link rel="stylesheet" href="/assets/circuit.css">
     <script src="/assets/circuit.js" defer></script>

   Optional, placed by hand between sections:

     <div class="rail-divider" aria-hidden="true">
       <span class="rd-node"></span><span class="rd-node"></span>
       <span class="rd-node"></span><span class="rd-node"></span>
     </div>

   Behaviour: the train draws a route, leaving lit track behind it, then holds
   and fades. At each pass boundary it picks a different branch at the
   junctions and the whole layer crossfades to a new colour.
   ========================================================================== */

(function () {
  "use strict";

  var PALETTE = [
    "--circuit-cyan",
    "--circuit-green",
    "--circuit-pink",
    "--circuit-ash",
    "--circuit-lavender",
    "--circuit-cherry",
    "--circuit-yellow",
  ];

  /* Three routes down the rail. All start and end at the same points and all
     carry pathLength="1000", so the dash is at the same progress on every one
     and swapping at a pass boundary is invisible. The static layer draws all
     three, so a switch reads as taking a line that was always there. */
  var ROUTES = [
    "M66 -20 V140 L34 172 V560 L66 592 V920",
    "M66 -20 V300 L98 332 V700 L66 732 V920",
    "M66 -20 V920",
  ];

  /* Switch points, where the routes diverge and rejoin. */
  var NODES = [
    [66, 140], [34, 172], [66, 300],
    [98, 332], [66, 592], [66, 732],
  ];

  /* Crossties along the trunk â the detail that makes it read as rail
     rather than as a plain circuit trace. */
  var TIES = [40, 96, 208, 264, 376, 432, 488, 648, 704, 816];

  function railMarkup(side) {
    var statics = ROUTES.map(function (d) {
      return '<path d="' + d + '"/>';
    }).join("");

    var ties = TIES.map(function (y) {
      return '<line x1="54" y1="' + y + '" x2="78" y2="' + y + '"/>';
    }).join("");

    var nodes = NODES.map(function (point, i) {
      return '<circle cx="' + point[0] + '" cy="' + point[1] +
             '" r="2.6" class="d' + (i % 4) + '"/>';
    }).join("");

    // Two paths per route: the track the train lights up behind it, and the
    // bright head riding the leading edge.
    var routes = ROUTES.map(function (d) {
      return '<path d="' + d + '" pathLength="1000" class="trail"/>' +
             '<path d="' + d + '" pathLength="1000" class="head"/>';
    }).join("");

    return '<svg class="circuit-rail is-' + side + '" viewBox="0 0 132 900" ' +
           'preserveAspectRatio="xMidYMin slice" focusable="false" aria-hidden="true">' +
             '<g class="ties">' + ties + '</g>' +
             '<g class="trace-static">' + statics + '</g>' +
             '<g class="routes">' + routes + '</g>' +
             '<g class="nodes">' + nodes + '</g>' +
           '</svg>';
  }

  function randomInt(min, max) {
    return Math.floor(Math.random() * (max - min + 1)) + min;
  }

  function build() {
    if (document.querySelector(".circuit-field")) return;

    var field = document.createElement("div");
    field.className = "circuit-field";
    field.setAttribute("aria-hidden", "true");
    field.innerHTML = railMarkup("left") + railMarkup("right");
    document.body.appendChild(field);

    return { field: field };
  }

  function start(parts) {
    var rails = parts.field.querySelectorAll(".circuit-rail");
    var root = document.documentElement;

    var colourIndex = randomInt(0, PALETTE.length - 1);

    function applyColour() {
      var token = PALETTE[colourIndex];
      root.style.setProperty("--circuit", "var(" + token + ")");

      /* The sync dot follows the circuit colour, and a red dot beside a
         healthy feed reads as broken. Cherry is held back from the echo only;
         the rails still run it. */
      root.style.setProperty(
        "--circuit-echo",
        token === "--circuit-cherry" ? "var(--accent-strong)" : "var(" + token + ")"
      );
    }

    /* Pick a branch on each rail independently, so the two sides are never
       running the same shape at the same time. */
    function switchRoutes() {
      rails.forEach(function (rail) {
        var paths = rail.querySelectorAll(".routes path");
        var perRoute = 2;                       // trail + head
        var routeCount = paths.length / perRoute;
        var choice = randomInt(0, routeCount - 1);

        paths.forEach(function (path, i) {
          path.classList.toggle("is-running", Math.floor(i / perRoute) === choice);
        });

        // Flash the junction the train just took.
        var nodes = rail.querySelectorAll(".nodes circle");
        if (nodes.length) {
          var node = nodes[randomInt(0, nodes.length - 1)];
          node.classList.remove("is-thrown");
          void node.offsetWidth;                // restart the animation
          node.classList.add("is-thrown");
        }
      });
    }

    applyColour();
    switchRoutes();

    /* One pass has finished: fresh branches and a fresh colour. The whole path
       fades to nothing at the end of a cycle, so both changes land while there
       is nothing on screen to jump. */
    function onPassBoundary() {
      var next = colourIndex;
      while (next === colourIndex) next = randomInt(0, PALETTE.length - 1);
      colourIndex = next;
      applyColour();
      switchRoutes();
    }

    /* Passes are counted from the timestamp rAF hands us. An element used
       purely as a clock gets parked off-screen, where browsers throttle its
       animation and currentTime stops advancing, so the count cannot hang off
       one of those. rAF also stops in a background tab, which is what we want:
       the layer costs nothing when nobody is looking.

       The count drifts slightly against the CSS animation's own start, but all
       three routes share a trunk for their first ~17%, so a switch anywhere in
       that window is invisible â far wider than the drift. */
    var lastPass = -1;
    var origin = null;

    function passDuration() {
      var raw = getComputedStyle(document.documentElement)
        .getPropertyValue("--circuit-pass").trim();
      var value = parseFloat(raw);
      if (!value) return 13000;
      return raw.indexOf("ms") > -1 ? value : value * 1000;
    }

    function tick(timestamp) {
      if (origin === null) origin = timestamp;
      var pass = Math.floor((timestamp - origin) / passDuration());
      if (lastPass === -1) lastPass = pass;
      else if (pass > lastPass) { lastPass = pass; onPassBoundary(); }
      requestAnimationFrame(tick);
    }

    requestAnimationFrame(tick);

    /* A background tab should cost nothing. */
    document.addEventListener("visibilitychange", function () {
      var state = document.hidden ? "paused" : "running";
      parts.field.querySelectorAll(".routes path, .nodes circle").forEach(function (el) {
        el.style.animationPlayState = state;
      });
      document.querySelectorAll(".rail-divider .rd-node").forEach(function (el) {
        el.style.animationPlayState = state;
      });
    });
  }

  function init() {
    var parts = build();
    if (!parts) return;

    // Honour the OS setting: the CSS already stops every animation, so there
    // is nothing to drive. One colour is still chosen, at random, per load â
    // with the same cherry guard on the echo that start() applies.
    var still = window.matchMedia("(prefers-reduced-motion: reduce)");
    if (still.matches) {
      var token = PALETTE[randomInt(0, PALETTE.length - 1)];
      document.documentElement.style.setProperty("--circuit", "var(" + token + ")");
      document.documentElement.style.setProperty(
        "--circuit-echo",
        token === "--circuit-cherry" ? "var(--accent-strong)" : "var(" + token + ")"
      );
      parts.field.querySelectorAll(".routes path").forEach(function (path, i) {
        if (i < 2) path.classList.add("is-running");
      });
      return;
    }

    start(parts);
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", init);
  } else {
    init();
  }
})();
