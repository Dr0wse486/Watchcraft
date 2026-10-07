# Watchcraft

![Minecraft](https://img.shields.io/badge/Minecraft-1.21.1-62b47a?style=flat-square)
![NeoForge](https://img.shields.io/badge/NeoForge-21.1.251-e8a33d?style=flat-square)
![License](https://img.shields.io/badge/license-MIT-4b8bbe?style=flat-square)

**Surveillance tools for Minecraft 1.21.1 + NeoForge.**

Watchcraft is about seeing places you are not. It gives you eyes you can leave behind: gear that
watches a room, a road or a player's back while you are somewhere else entirely, and that tells
you what it saw without asking you to stand there and look.

The first tool is a **recon drone** — deploy it, link into it from anywhere, fly it like you fly
yourself, and every creature that wanders into its view gets lit up so you can mark it. Throw one
with a single key to scout ahead without ever leaving your body.

It is not meant to stay the only one. **Pinhole cameras and other instruments are on the way**, and
the drone is built to be the reference the rest of them follow rather than a one off.

![The drone, four views](docs/drone-preview.png)

## Tools

| Tool | Status | What it gives you |
| --- | --- | --- |
| **Recon drone** | Shipping | A flyable camera on a 64 block leash. Deploy it, link in from anywhere, and everything it looks at gets outlined. Takes modules, up to and including a warhead, and a battery to be of any use. |
| **Pinhole camera** | Planned | A camera mounted on a wall and tuned into rather than flown. No flight and no leash — just one fixed view of a room, from a spot nobody thinks to check. |
| **Viewing terminal** | Idea | A block that shows the feed of any instrument you have linked. Somewhere to actually watch from. |
| **Motion sensor** | Idea | A tripwire with a cone. Reports movement across its whole field instead of lighting up a single block. |
| **Tracker tag** | Idea | A dart that sticks to a creature and keeps reporting where it went. Marking, but permanent. |

Status is intent, not a schedule. Shipping means it is in the jar today; planned means it is the
next thing up; idea means it is being kicked around and may change shape or never happen.

## What ties them together

Every instrument is meant to plug into the same handful of systems, so that adding one feels like
adding a part rather than adding a mod:

- **The leash and the signal model.** Distance degrades the feed and past the leash you lose it.
  Already built for the drone, and the shape every remote instrument reuses.
- **The visor.** One HUD, one reticle, one key strip, drawn *under* the signal overlay so the
  instruments stay readable when the picture does not.
- **The Module Workbench.** One bench, one loadout mask carried on the item stack, one
  prerequisite rule. New instruments bring new boards.
- **Server authority.** Clients send intent and nothing else. Ownership checks, packet rate caps
  and collision sweeps all happen on the server.
- **Generated assets.** Every mesh, texture and shader comes out of `tools/`. A new instrument is
  a new generator, not a new binary blob.

## The recon drone

The rest of this README is the drone — the reference implementation, and the only instrument
shipped so far.

### Controls

| Key | Action |
| --- | --- |
| Right click a block | Deploy a drone hovering in front of that face |
| Right click air | Throw a drone forward (same as the throw key) |
| Sneak + right click a drone | Pack it back up into the item |
| Sneak + right click *with a battery in either hand* | Fit the battery, or pull the one already in it |
| **V** | Link in / link out (camera hops to the drone) |
| **G** | Throw the drone in your hotbar, or the first one in your inventory |
| **R** | Recall the drone — folds it back into your inventory from any distance |
| **W A S D** | Fly — forward is *where the camera points*, pitch included |
| **Space / Shift** | Climb / descend, straight up and down in world space |
| Right click *while piloting* | Drone reaches out and works a door / trapdoor / fence gate in front of it |
| **C** *while piloting* | Roller — tap for one full roll, hold to spin on the spot |
| **Sprint key** *while piloting* | Charge attack — needs an Attack Module fitted (see below) |
| **X** *while piloting* | Detonate the warhead where it stands — needs an Attack Module fitted |

All five mod keys are rebindable under *Options → Controls → Recon Drone*. The charge uses
vanilla's own sprint binding, and the visor spells it out.

### Commands

| Command | Effect |
| --- | --- |
| `/watchcraft doorinteract` | Print whether pilots may open doors |
| `/watchcraft doorinteract true` | Let pilots open doors, trapdoors and fence gates |
| `/watchcraft doorinteract false` | Turn that off — right click does nothing from the cockpit |
| `/watchcraft dimension` | Diagnose dimensions: where you are, where the drone is, and whether the guard fired |

The door setting is stored with the world, so it survives a restart.

`/watchcraft dimension` exists because the automatic recall is a *side effect*: once it has run, the
drone is simply gone, and you cannot tell whether it went because the dimension check worked or for
some other reason. The command prints the recorded dimension and the current one separately from
the outcome, so "the check noticed" can be confirmed on its own.

### How it behaves

- **One drone at a time.** You can only ever have a single drone out in the world, and only
  that drone can be piloted. Trying to deploy or throw a second one just prints a message.
- **Remote control.** While linked, your body stops dead where it is, keeping the heading it had
  when you linked in — the body and head angles are pinned along with the yaw, so the player model
  does not swing around while you fly. Mouse look drives the drone, and the drone's camera is fully
  first person: your own arm and held item are hidden.
- **Clicks stay in the cockpit.** Left click, right click, middle click and the scroll wheel all
  do nothing to the player while piloting — no items used, no blocks placed, nothing attacked,
  no hotbar slot changed. A right click instead casts a 5 block reach **from the drone**; only
  doors, trapdoors and fence gates respond. Boats, beds, chests, workbenches and everything else
  are deliberately out of reach.
- **The inventory is off limits too.** `E` and the `1`–`9` hotbar keys are swallowed while
  piloting, along with `Q` and `F`. This is a cosmetic rule rather than a mechanical one: an
  inventory screen obviously breaks the visor, and even a plain hotbar change is enough, because
  vanilla pops the item's name up in the middle of the screen for a moment afterwards. Linking in
  from behind an open screen closes it.
- **The view sets the direction, not the speed.** Forward is the full view vector with pitch
  included: aim at the sky and hold forward and the drone climbs, aim at the ground and it
  descends. `A` / `D` sidestep along the flattened heading so they never peel off into a dive,
  and `Space` / `Shift` are plain world-space vertical. Diagonals are normalised so holding two
  keys does not stack past cruise speed. **With nothing held the drone does not move at all** —
  turning the camera while parked will not budge it, which is the difference between flying a
  recon drone and flying a helicopter.
- **Momentum.** The keys feed a *target* velocity, and the real velocity chases it quickly when
  accelerating but slowly when slowing down. That asymmetry is the whole trick: let go mid
  flight and the drone keeps drifting about a block over half a second before it settles,
  instead of stopping dead. Hit a wall and the coast is dropped so it does not grind along the
  surface.
- **Banking.** Turning rolls the drone into the turn. Every client derives that lean from the
  rotation stream it already receives, so it costs no extra packets and bystanders see exactly
  what the pilot sees.
- **It can be shot down.** The airframe has **10 hit points** (five hearts). Any damage source
  that is not blocked by invulnerability hurts it, with a 10 tick immunity window after each hit
  so it cannot be burst down in one combo. Hits flash it red and clank. At zero it goes down in
  smoke and sparks, drops its airframe for the owner to pick back up, and the link drops
  instantly. Whatever modules were fitted come back with it.
- **Immune to every status effect.** This is structural rather than bolted on: the drone extends
  `Entity`, not `LivingEntity`, so potions, area effect clouds and `/effect` all resolve their
  targets as a `LivingEntity` and never reach it. It is also `fireImmune()`, so fire cannot
  touch it either.
- **Visor readout.** The reticle is a single dot with a one pixel dark halo, so it stays legible
  against snow, cloud and sky. The HUD shows exactly four numbers: the drone's X, its Y, its
  distance from you, and its remaining hit points. The distance turns amber once you pass 75% of
  the link leash; the hit points turn red below 30%. The key strip along the bottom replaces the
  vanilla experience bar while piloting.
- **Signal breakup.** The picture starts to hiss at **48 blocks** and degrades one grade every
  **4 blocks**, so 52 is a quarter gone, 56 half, 60 three quarters, and at **64** — the end of
  the leash — the feed whites out and a blinking `SIGNAL LOST` banner takes over.
  The defocus is a **real** one: the world is blurred before the GUI is drawn, by a post chain
  (`assets/watchcraft/shaders/post/drone_link.json`) whose passes all name vanilla's own
  `box_blur` program, with the radius driven off the same 0-to-1 degradation figure the overlay
  uses. Nothing is compiled or registered from Java — it is one JSON file and a uniform write.
  It runs at `RenderLevelStageEvent.AFTER_LEVEL`, which is *before* `doEntityOutline` composites
  the glow of every marked entity, so the world goes soft while **the glow and the readouts stay
  crisp**. That ordering is load bearing rather than incidental: a glowing entity is never drawn
  into the main target at all, only into the outline buffer, so what you actually see is a thin
  white edge — blurring it drops its peak brightness by an order of magnitude and the mark
  collapses into a dark smear.
  The overlay on top of the blur is an analogue style mix of a milky wash, a stacked vignette,
  a few torn horizontal scan lines and **one pixel** grain, reseeded 25 times a second so it
  flickers like a struggling transmission instead of averaging into flat grey at high frame rates.
  The grain is deliberately fine and the softness comes from the shader: a screen space imitation
  can lift the blacks and darken the corners, but `GuiGraphics` only fills flat colour, so it can
  never actually soften an edge — and contrast lifting alone just reads as dust on a sharp
  picture. The lines are punctuation, not the effect: real analogue interference is overwhelmingly
  speckle, and an earlier pass that drew ten of them per grade read as venetian blinds rather than
  as a failing feed. The whole effect is drawn *under* the frame and readouts, so you lose the
  picture, not the instruments.
- **Your body stays put.** Linking in mid sprint, mid jump or mid walk does not leave the player
  skidding, drifting or strolling on their own. Sprint is cancelled, the movement input triple is
  zeroed, the sideways drift is dropped every tick and the fall distance is cleared, so the body
  simply drops to the ground under its own weight and lands for free. Zeroing the input has to be
  done on the body rather than in the movement input event: vanilla only copies the event's values
  into the body while the camera is on it, so with the camera on the drone a leftover input value
  is never overwritten, only decayed — a body linked in mid walk keeps walking, gently, for a few
  seconds. The *vertical* velocity is deliberately left alone: gravity is only worth 0.078 blocks
  a tick, so cancelling the velocity every tick would cancel the acceleration with it and the body
  would sink at walking pace, which reads as floating. The position is never written to at all —
  an earlier version pinned the body onto the spot it landed on, and the pin engaged at exactly
  the tick it touched down, fighting the landing physics and showing up as a stutter. It bought
  nothing anyway, since a body with no input and no sideways velocity has nothing left to move it.
  The body's position is reported to the server every tick it changes, because vanilla stops
  reporting a player's position the moment the camera moves to something else — without that, the
  whole drop would arrive as one jump on the tick you dismount and be charged as fall damage.
- **Marking.** Any living entity inside a 110° cone, within 48 blocks, with an unobstructed line of
  sight is tagged as glowing for 60 ticks. Keep it in view and the glow keeps refreshing; look away
  and it fades out about three seconds later. Anything within 8 blocks is lit regardless of the
  angle — it is in frame whether or not the drone happens to be pointing at it. The sweep runs every
  two ticks, and line of sight is tested against the nearest face of the target's box before its
  centre, so a fence post or a lip of ground between the two does not hide a target the drone can
  plainly see. The cone and the reach are the two numbers the Recon Boost module stretches — fit it
  and they become 165° and 72 blocks — and everything derived from them, including the range at
  which the picture starts to hiss, stretches with them.
- **Thrown drones scout too.** A thrown drone flies ballistically, points itself along its
  flight path and keeps scanning, then hovers where it ends up. You can link into it later. A
  thrown drone scans with a wider 150° cone and a 16 block no-aim radius, because it tumbles along
  its arc and a cone tight enough to feel like aiming slides straight past what it just flew over.
- **Link leash.** If you walk more than 64 blocks away, or the drone is destroyed, or you
  die, the link drops automatically and your camera returns to your body. Signal modules buy
  thirty-two blocks each, so a fully boosted leash is 128.
- **Server side validation.** Movement is client driven (like the vanilla player) but the
  server clamps the distance per tick, enforces the leash, and requires you to own the drone
  before it will accept a link request. Glow scanning and door opening both run on the server,
  so they cannot be faked by a modified client. Move packets get six checks: owner, finite
  numbers, per packet step, leash range, **one packet per tick**, and a **block collision sweep**
  run with the same routine `Entity#move` uses. The step limit alone bounds a single packet rather
  than the packet rate, so the per tick cap is what stops a client from buying speed by
  spraying packets; the sweep is what stops it from claiming to be on the far side of a wall.
  A stock client has already run that exact sweep before sending, so neither check costs it
  anything. Mid charge the packet is still accepted — it carries the aim, which the server clamps
  onto the run's cone — but the position in it is discarded and the leash check is skipped, since a
  committed one way run covers far more ground than the link reaches.

### The screen

The drone's camera is a camera, and the picture says so. Everything the world renders while you are
linked is passed through an **LCD filter** before the visor goes on, and what it draws is a real
panel rather than a few scanlines:

- **A black matrix.** One device pixel is darkened along the bottom of every cell. The *gap* is
  always exactly one pixel wide and only the cell size changes — a wider cell means a bigger
  opening, not a thicker bar, which is what a real panel does and what keeps the effect reading as
  a screen instead of as venetian blinds.
- **A subpixel mask.** One device pixel of red, then green, then blue, cycling every three pixels.
  The three stripes are exactly balanced, so this changes the colour of a column without changing
  the brightness of the picture — which is what an LCD looks like magnified.
- **A bezel.** The outermost strip of the screen is darkened. This is the bluntest cue of the lot
  and the one that survives any picture content, because it does not depend on what is being shown.
- **Backlight falloff, a cool cast and a lifted black.** LCD blacks glow rather than being black.
- **A bright band rolling down the panel**, once every five seconds, plus a 24 Hz brightness jitter.
- **A little colour fringing toward the corners**, and a half pixel softening. Both are lens cues
  rather than panel cues, and both are strongest where the eye minds them least.

Two things about it are load bearing and easy to get wrong. There is **no vertical matrix column**,
even though a grid of squares is the obvious mental picture: a one pixel column always falls wholly
inside a single stripe — the cell's last column is always the blue one — so that channel gets
darkened twice and the picture goes yellow-green. And the stripe cycle is a **fixed three pixels**,
not "whatever the cell is divided by three": three stripes only divide a cell evenly when the cell is
a multiple of three wide, and at the shipped pitch of four, dividing would give red, green, green,
blue and paint the whole picture green. Tying the stripes to the pixel instead of to the cell makes
them exactly balanced by construction, and is also just true of real panels.

The grid eats brightness — one row in `p` loses `1/p` of its area — so the mean is divided back out.
The screen should give you *structure*, not exposure: making the whole world dimmer is a different
effect, and one that makes caves unplayable.

It is a real shader pass rather than an overlay, and that is not a stylistic choice. `GuiGraphics`
only fills flat rectangles, so it cannot draw a subpixel — and lines drawn in screen space read as
lines lying *on top of* the picture rather than as gaps in the panel the picture is shown on. The
difference shows the moment you turn the camera: an overlay's lines stay put while the world slides
underneath them. Panel structure has to be a property of the pixels, so it is computed per pixel in
`assets/watchcraft/shaders/program/lcd.fsh` and knows nothing about what is being displayed.

It hangs off the same post chain as the signal blur, at the same `AFTER_LEVEL` hook, which puts it
**before** `doEntityOutline`. So the filter is applied to the world only: the glow of every marked
entity, the reticle, the readouts and the key strip are all composited onto an already filtered
picture and stay perfectly sharp. That is the same layering the whole visor is built on — you lose
the picture, not the instruments.

None of it is driven by distance. Full signal or none, the panel looks the same: the signal model
decides how *bad* the feed is, the panel decides what the feed is being shown *on*. And it is
strictly a pilot's view — walk around as yourself and there is nothing to see.

Three knobs, all client side, all live, all under **Visuals** in the settings screen:

| Option | Default | Effect |
| --- | --- | --- |
| `lcd.enabled` | on | Turns the pass off; the shader then takes a single-sample shortcut |
| `lcd.strength` | 1.0 | Weight on every ingredient at once. Around 0.5 it is "something feels off about this picture" rather than "I am looking at a screen" |
| `lcd.pixelPitch` | 0 (auto) | Cell size. 0 follows the resolution — one cell is about 1/270 of the screen height, so roughly 4 at 1080p, 5 at 1440p and 8 at 4K. A nonzero value is an explicit size in device pixels, with a floor of 4 |

Following the resolution by default is not laziness. The pitch is a *physical* quantity, but this
effect is a *stylised* one: the same four pixel cell that reads correctly at 1080p is fine enough to
vanish completely at 4K, and a panel you cannot see is not doing anything. The floor of four is
about proportion rather than geometry — below that a cell is more gap than opening, and it stops
reading as a panel and starts reading as blinds.

### Modules

A drone is a bare airframe until it is fitted out. Modules are seated at the **Module Workbench**:
put a drone in the left well, a board in the middle one, and the finished drone appears in the
right one to be taken out. The bench takes one board at a time and never destroys anything — close
the screen with something still in it and it comes back to you.

| Module | Requires | Effect |
| --- | --- | --- |
| **Drone Customization Module** | — | The fitting bay. Nothing else can be installed without it. |
| **Recon Boost Module** | Customization Module | Widens the scan cone (110° → 165°) and the reach (48 → 72 blocks). Gates the two boards below. |
| **Container Marker Module** | Customization + Recon Boost | Marks nearby containers on the owner's screen — chests, barrels, shulkers, hoppers, furnaces. |
| **Alert Radar Module** | Customization + Recon Boost | Warns the owner of creatures closing in: monsters in amber, other players in red. |
| **Signal Module I** | Customization Module | Thirty-two more blocks of link leash. |
| **Signal Module II** | Customization + Signal I | Another thirty-two, so both tiers together give sixty-four. |
| **Governor Removal Module** | Customization Module | Raises the cruise ceiling to 1.5× a sprinting player. |
| **Attack Module** | Customization Module | Charge attack from the cockpit. |

The prerequisite is real: the bench simply produces no output for an Attack Module on a drone that
has not been opened up first, and a board the drone already carries produces no output either — one
of each, in the right order.

**The recon package is a gate rather than a stat stick.** The container scanner and the radar are
useless on a short sighted airframe, so both of them refuse to seat until the recon boost is on
board — but the boost also widens the cone and the reach on its own, so fitting it is worth doing
before either of its dependants exists. The reach and the cone are scaled together on purpose:
stretching only the radius gives a long thin tube, stretching only the cone gives a wide but
near sighted one, and neither of those reads as "sees further".

**The sensors are module gated, not config gated.** Container marking and threat alerting each
need their board fitted *and* the server's master switch on; either one missing and the drone
simply reports nothing. The client never decides what is marked — it only draws what the server
sends, so a modified client cannot turn a bare airframe into a radar.

**Being seen is a two way street.** A player the drone actually marks — inside the cone, with line
of sight — gets a banner across the top of their screen with the head of whoever is flying it and
the words *drone detected*. It is on by default, not a module: the drone tags people, so people get
to know they have been tagged. The server throttles it to one banner every two seconds per player,
and the signature falls back to the owner when nobody is at the controls. The pilot, meanwhile, gets
their **own head** drawn over their body — the camera is on the drone and the body can be hundreds
of blocks away, so without it "go back" has no direction. It used to be a hollow white bracket, and
that was a mistake: a bracket and the container marker are both little squares, and at range the two
were indistinguishable. A face can only be a person. The head follows **the pilot the drone says it
has**, not whatever entity the client thinks it is playing as — a fake-player mod can swap the
client's player out from under the link, and the marker would otherwise land on the stand-in.

**And the feed shows who the drone has caught.** Every player the drone is currently marking gets a
**rotating hollow white square** over them — in the drone's view while you are flying it, and in your
own view while you are not, exactly like the container markers. The glow outline alone says "something
over there is lit up"; it does not say that the something is a person, or that the drone is the one
looking at them. The rotation is the part that matters — a still square would collide with the
container marker exactly the way the old pilot bracket did, and a turning one reads as a target being
tracked. It is the same treatment as the pilot's own body marker and deliberately not the same shape:
a head means *this is me*, a spinning square means *this is not*.

**The loadout travels with the airframe.** Deploy it, throw it, get it shot down, pack it back up,
save and reload — the drone comes back with the same boards in it. That is why the mask lives on
the item stack rather than on the entity: every one of those transitions builds a brand new
`ItemStack`, and anything kept only on the entity would be gone at the first one.

#### Charge attack

With an Attack Module fitted, press the sprint key while piloting. The drone spools up and commits
to a **straight run along whatever the crosshair was pointing at** — no pitch floor, so a level aim
flies level. From that moment the airframe belongs to the server: it owns the position and flies the
line itself, at roughly six times cruise speed, for up to three seconds or until the nose finds
something.

The pilot is not a passenger, though. The axis is locked at launch but the aim can be **bent up to
16° off it**, so the run is a committed charge you can still nudge onto a moving target rather than
a ballistic missile. The aim is **damped** rather than bolted to the mouse: the raw movement feeds a
desired angle and the crosshair chases it on a short first order lag, the same shape as vanilla's
cinematic camera. On a cone this tight that is what makes it feel *tight* rather than twitchy — the
crosshair spends its time near the middle of the envelope instead of slamming into the edge and
sticking there. At the edge further movement is simply discarded, so bringing the mouse back moves
the crosshair at once with no invisible backlog to unwind.

The client clamps to that 16° cone and the server clamps to a deliberately wider 22° envelope, so an
honest pilot is never clamped twice and a modified client gets a slightly longer nudge rather than a
free turn. Both sides clamp against the same published axis, which is what stops the crosshair and
the airframe's real heading from drifting apart.

The **view pulls open by 30%** for the duration, eased in over a fraction of a second rather than
snapped, so the run reads as acceleration. It is a plain `ViewportEvent.ComputeFov` hook, applied at
the very tail of `GameRenderer#getFov` — after the configured FOV, the sprint animation and
`fovEffectScale` — so nothing downstream recomputes the projection and throws the change away.
**Speed streaks** sweep outward from the middle of the screen at the same time, driven by the same
ramp so the two cannot come in or out of step. Each streak keeps its own lane and its own speed —
the angles come from a hash of the slot index rather than a random source, because a fresh random
angle every frame reads as noise rather than as motion — and each fades in as it leaves the middle
and out again as it reaches the edge. Both halves are drawn under the frame and the readouts, so the
instruments stay crisp while the picture tears past.

Whatever the nose touches first takes **20 points of explosive damage**, and the blast breaks a
small crater.

Protection and Blast Protection reduce that exactly as they would a creeper: the damage is delivered
as an explosion, just with a flat figure instead of vanilla's distance falloff. That override is the
point — a warhead that does twenty at the point of impact and four at the edge cannot be aimed at
anything in particular. The crater is kept small by clipping *block* damage to the impact point,
not by shrinking the blast: the radius still governs who gets caught.

**Nothing survives the run.** The airframe, the rotors and both boards go up with the warhead — a
charge is a one way trip, so spending it costs the drone outright. Being shot down is the cheap
ending; this is not.

It is also entirely server side. The client only asks; the server checks that the warhead is
actually fitted, that the caller is actually the pilot, and that the warhead is off cooldown before
committing. Mid run the server flies the airframe and discards the pilot's reported position — the
pilot's copy of the drone chases the server's rather than snapping to it, so the run renders at
frame rate instead of stepping at tick rate. Only the rotation in that packet is used, and only
after it has been clamped onto the cone.

There used to be a second trigger — a double tap of forward — and it is gone. With the run no longer
a stoop, forward is the axis the pilot is about to lock, and putting a destructive action on the same
key that flies the drone was a bad idea.

### Roller

Press **C** while piloting and the drone rolls about its own sight line. A **tap** is one clean
revolution in about half a second — a stunt, with a beginning and an end. **Hold** and it keeps
turning instead, one and six tenths revolutions a second, until you let go: the airframe winds
itself into a drill and stays there. That difference is the whole point of the key. A barrel roll
that is always one revolution is a gesture; a roller you can lean on is a way to fly.

Holding is also the only way to beat the cruise governor. With the roller spinning *and* the sprint
key down, the drone dashes at 15% over its normal ceiling, and the view pulls open by 12% on the
same ramp so the extra speed reads as a change of posture rather than as the world suddenly moving
faster. The ceiling itself is untouched: the dash is a multiplier on it, so "how fast can this thing
go" is still one constant.

Let go and the horizon does not stay where you left it. This is the part that is easy to miss. The
view is genuinely rotated while you hold, so releasing could stop at 47° or at 313°, and leaving it
there would hand the pilot a permanently crooked horizon that got a little worse with every roll. So
releasing forces a recovery: the view unwinds to level along the shortest arc, from wherever it
happened to be, in about four tenths of a second. A tap needs no recovery because a tap always runs
a full 360° and lands back where it started — and for the same reason the cooldown only applies to
taps. A held roller never locks you out, or letting go would leave you unable to start the next one.

The whole roller lives on the client. It changes this side's speed ceiling and this side's field of
view and nothing else, and it needs no synced state to do it: the server's per-packet step cap is
1.8 blocks while even a dashing drone moves about 0.28, so a legitimate overshoot never comes close
to being rejected.

### Batteries

A drone does not need a battery to fly. It needs one to be *usable*. Fit an airframe and leave the
pack out and the feed is a wall of snow, the sticks do nothing at all, and a banner tells you so —
that is deliberate, because "no battery" has to be a state you can see rather than one you have to
remember.

| Item | Charge | Flight time |
| --- | --- | --- |
| **Copper Battery** | 25% | About 12.5 minutes |
| **Graphite Electrode** | — | Half of a graphite pack; the recipe takes two |
| **Graphite Battery** | 100% | About 50 minutes |

One charge unit is one percent of capacity, and a unit drains every **600 ticks** — thirty seconds —
so the numbers the design talks in are the numbers the HUD prints, with no conversion anywhere in
between. Drain is a server side tick, so a drone parked in an unloaded chunk does not quietly empty
itself while nobody is watching.

The pack travels with the **item**, not with the entity. It lives in an `ItemStack` component the
same way the module mask does, which means deploying, throwing, being shot down and packing back up
all keep the charge with the airframe — a drone that comes back with a half spent pack comes back
carrying that same half spent pack.

Fitting is one key with two meanings: **sneak and right click while holding a battery**. Empty well,
and the one in your hand goes in; already full, and the one in the well comes out. There is one
battery slot, so "swap if there is one, seat it if there is not" is the only reading that makes
sense, and nobody has to remember two operations. If you are holding a battery while one is already
seated, what comes out is the *seated* one, with its charge, and the one in your hand is untouched —
which is what makes fitting strictly reversible rather than a way to lose a pack.

It works on the drone as a **stack in your hand** as well as on one already out in the world, and it
has to: the drone is unusable without a battery, so you need to be able to fit one *before* you ever
deploy it. On the item, sneak and right click it with the battery in your **other** hand, since the
clicking hand is already holding the drone. On a deployed drone **either hand** works — the clicking
hand first, then the other one — because vanilla's right-click loop gives up after the first hand
that consumes the interaction, so a battery in the off hand would otherwise never be seen and the
click would fall through to packing the drone away. Fitting a deployed drone means unlinking first
(**V**), because the cockpit's controls are dead until the pack is in.

Recalling takes **three seconds** rather than being instant. The airframe freezes and a progress bar
runs across the visor while it winds in, so a recall is something you commit to rather than an escape
hatch you can tap mid dive. The client freezes with it — otherwise it would keep flying a drone the
server has already stopped, and the three seconds would end with the camera snapping back. Changing
dimension is the one exception and still recalls immediately: a drone left behind in a dimension
whose chunks no longer tick is a drone you can never get back.

A drone with no battery fitted also does not drain and does not auto recall. It simply flies as badly
as it always did. Reading "no battery" as "zero charge" and recalling on the spot does not work,
because the player has to be able to deploy a bare airframe before there is anywhere to fit a battery
to it.

### Recon feed

Two boards turn the drone from a camera into a sensor package. Both report to the drone's **owner**
rather than to whoever is flying it — the drone can be out doing its rounds while you are somewhere
else entirely, so the feed follows the airframe, not the cockpit. The client never decides what is
marked; it draws what the server sends, so a modified client cannot promote a bare airframe into a
radar.

**Container Marker Module** marks containers within **32 blocks of the drone**: chests, trapped
chests, barrels, shulkers, hoppers, droppers, dispensers, crafters, the furnace trio, brewing stands
and ender chests. That list is one `instanceof` against `BaseContainerBlockEntity`, which is why
barrels come along for free — they are not chests in the class hierarchy, and a scanner that found
chests but not barrels would be missing the one container that is a chest in every way that matters.
Ender chests are named separately because they inherit `BlockEntity` directly.

A box buried in a wall is not a find: a container has to show at least **two of its six faces**
before it counts, which is what keeps the scanner from being a wallhack. The test for a face is
whether the neighbour is *solidly* rendered, not whether it is another container — a chest is not a
full block, so the face where two chests touch counts as exposed on its own and double chests need
no special case. Past that filter one layer of blocking is tolerated, and that leniency is load
bearing rather than sloppy: a drone hovering overhead looking down at a roofed chest, or at any chest
indoors, would otherwise fail the raycast on the most ordinary placement there is and the board would
do nothing. The sweep runs every **10 ticks**, keeps the nearest **32**, and never loads a chunk to
look — a drone is not a chunk loader.

**Alert Radar Module** is the other half, and it is aimed at the wrong end of the leash on purpose:
its **32 block radius is centred on the owner, not on the drone**. The thing worth protecting is the
player, and a radar that only watched the drone's own surroundings would go quiet exactly when the
drone was away scouting. It reports monsters in **amber** and other players in **red**, ramping from
24 blocks away to full at 6, and it runs every **4 ticks** — four times faster than the container
sweep, because containers do not walk.

Neutral mobs are filtered out through `NeutralMob`. Vanilla's `Enemy` marker means "implements
`Monster`", not "will attack you", and the enderman and the zombified piglin both carry it while
being perfectly happy to ignore you. Line of sight is **not** required here, the opposite of the
container rule — a monster that has just stepped around a corner is precisely the case worth warning
about.

Both boards need the server's master switch *and* the module fitted, and either one missing leaves
the drone reporting nothing. The results are a **snapshot**, rebuilt from scratch every sweep, so a
container that leaves range simply disappears and a recalled drone leaves nothing behind. There is no
cross session storage and therefore no stale marker to clean up.

**"Our side" is more than one entity.** The alert leaves out the owner and the pilot, and it
identifies them by UUID and by entity id. A **fake player** — the kind a testing mod spawns so you
can drive a second body — is a genuinely separate `Player` with its own UUID and its own entity id,
so neither check catches it, and the drone ends up reporting a copy of its own operator as a hostile
stranger. The one thing a fake player does share with the player it copies is the **name**, so that is
the third check: a player whose profile name matches the owner's or the pilot's counts as being on
the drone's side. It is a heuristic, and it is the only signal the server has — `alert.ignoreNamesake`
turns it off for a server where two real players might share a name.

What the drone *cannot* do is tell which body your client is currently driving. Ownership and the
link are both anchored to the `ServerPlayer` behind your connection, so a fake-player mod that moves
your camera onto another entity without moving the connection leaves the drone talking to your real
body. That is a property of how the game identifies players, not something a mod can fix from the
outside.

The snapshot carries the marked players as **entity ids, not positions**. The entities are already on
the client, so the marker projects their live position every frame and follows them smoothly; sending
coordinates instead would make it step along at one packet every four ticks. The server is still the
only thing deciding *who* is marked — the client is told the list and draws it, nothing more.

### Crafting

The drone is assembled from two parts, and each part is crafted on its own first. Modules and
batteries are ordinary recipes of their own — the boards go on at the bench afterwards, and the
packs are fitted by hand.

```
Module workbench                   Drone chassis
 I I I     I = iron ingot           . I .     I = iron ingot
 I R I     R = redstone             I R I     R = redstone block
 I I I     . = empty                . I .     . = empty
```

```
Drone propeller                    Recon drone
 . N .     N = iron nugget          P . P     P = drone propeller
 I = iron ingot                     . C .     C = drone chassis
 . I .                              P . P
```

```
Customization module               Attack module
 . I .     I = iron ingot           . G .     G = gunpowder
 R A R     R = redstone             I T I     I = iron ingot
 . I .     A = amethyst shard       . G .     T = tnt
```

```
Recon boost module                 Container marker module
 . A .     A = amethyst shard       . A .     A = amethyst shard
 G S G     G = glass                G C G     G = glowstone dust
 . A .     S = spyglass             . A .     C = chest
```

```
Alert radar module
 . R .     R = redstone
 A S A     A = amethyst shard
 . R .     S = sculk sensor
```

```
Signal module I                    Signal module II
 . C .     C = copper ingot         . G .     G = gold ingot
 R A R     R = redstone             R E R     R = redstone
 . I .     A = amethyst shard       . A .     E = ender pearl
           I = iron ingot                     A = amethyst shard
```

```
Governor removal module
 . S .     S = sugar
 R G R     R = redstone
 . I .     G = gold ingot
           I = iron ingot
```

```
Copper battery                     Graphite electrode
 G G G     G = gold ingot           . I .     I = iron block
 C R C     C = copper ingot         I D I     D = diamond
 C R C     R = redstone             . I .
```

```
Graphite battery
 G G G     G = gold ingot
 E R E     E = graphite electrode
 C R C     R = redstone
           C = copper ingot
```

## Building

Needs a **JDK 21** — nothing else. The Gradle wrapper fetches its own distribution, and the
toolchain resolver downloads a JDK if the machine does not already have one.

```bash
./gradlew build          # jar lands in build/libs/Watchcraft-1.0.0.jar
./gradlew runClient      # dev client
./gradlew runServer      # dev server
```

## Assets

Every texture and model in the mod is generated from source by the scripts in `tools/`. They use
nothing but the Python standard library — no Pillow, no numpy, no 3D library — so the whole pipeline
runs on a bare interpreter.

The shaders are the exception and are hand written: `shaders/post/drone_link.json` is the pass
schedule, and `shaders/program/` holds the fragment programs it names — `radial_blur` for the
detonation and `lcd` for the panel. None of them are compiled or registered from Java, because a
post chain reads its files straight off the resource manager: adding a pass is editing a JSON file
and writing a `.fsh` beside it.

| Script | Does |
| --- | --- |
| `generate_drone_mesh.py` | Builds the OBJ/MTL pair and the UV manifest. The palette lives here and nowhere else. |
| `generate_textures.py` | Paints every PNG: entity sheets from the manifest, item and block icons, the workbench GUI, the pack logo. |
| `verify_mesh.py` | Checks the mesh against the modelling spec — winding, budgets, symmetry, coplanar faces, UV packing. |
| `verify_shaders.py` | Checks every post chain against the programs it names: that the programs and shaders exist, that no pass declares a uniform its program does not have, and that the chain ends writing back to `minecraft:main`. |
| `preview_mesh.py` | Renders the model to PNG offline, both render types, so a packing mistake shows up without launching the game. |
| `preview_static.py` | Renders the signal-loss overlay offline, blur included, for tuning the veil, vignette and grain. |
| `preview_lcd.py` | Renders the LCD filter offline: a 1:1 crop sheet for the panel structure, and a whole frame for the bezel and the vignette. |
| `paths.py` | Where everything lives, resolved once for all of the above. |

Every script runs with no arguments, from anywhere:

```bash
python tools/verify_mesh.py            # 0 failures, 0 warnings on a clean tree
python tools/verify_shaders.py         # 0 failures, 0 warnings on a clean tree
python tools/preview_mesh.py           # build/preview/drone-preview.png
python tools/preview_static.py         # build/preview/static-preview.png
python tools/preview_lcd.py            # build/preview/lcd-preview*.png
```

**Run `verify_shaders.py` after touching anything under `shaders/`.** A post chain is validated at
load time and it does not degrade gracefully: one bad entry and `PostChain` throws, the whole chain
never builds, and — because that exception is an `IOException` and `DroneSignal` used to catch and
discard it — the result is a perfectly clean log and three missing effects. That is not hypothetical;
it is how the signal blur, the detonation blur and the LCD filter all sat dead for a week. The
validator is one file and takes a moment.

Changing the model is a two command loop:

```bash
python tools/generate_drone_mesh.py --install   # build/meshgen, then copy into the repo
python tools/generate_textures.py
```

`--install` copies the OBJ/MTL pair to `src/main/resources/assets/watchcraft/models/entity/` and the
manifest to `tools/`, which is where the texture painter, the preview and the verifier all look for
it. The manifest is a hundred kilobytes of build metadata, so it lives beside the scripts rather
than inside the jar.

## Layout

```
src/main/java/dev/watchcraft/watchcraft/
  entity/     ReconDroneEntity (the airframe), DroneControlState
  item/       ReconDroneItem, DroneModuleItem, DroneModules (the loadout mask), BatteryItem
  block/      ModuleWorkbenchBlock
  menu/       ModuleWorkbenchMenu
  network/    payloads + server bound handling
  command/    ModCommands  (/watchcraft doorinteract, /watchcraft dimension)
  server/     DroneSettings (world scoped rules), DroneDimensionGuard (dimension recall)
  client/     DroneController, DroneHud, DroneSignal, DroneLcd, DroneMarkerOverlay,
              DroneDetectWarning, renderer, rig, meshes, sounds, keys, events, screens
  registry/   items, blocks, entities, menus, data components, sounds, creative tab

src/main/resources/assets/watchcraft/
  models/entity/    drone.obj + drone_glow.obj + their MTLs
  shaders/post/     the defocus and LCD pass schedule
  shaders/program/  radial_blur.fsh (detonation), lcd.fsh (the panel)
  textures/         generated
  lang/             en_us, zh_cn

tools/        the asset pipeline (Python, standard library only)
docs/         design notes, including the full modelling spec
```

## Tuning

Everything worth tweaking lives at the top of
`src/main/java/dev/watchcraft/watchcraft/entity/ReconDroneEntity.java`:

| Constant | Meaning |
| --- | --- |
| `MAX_DEPLOYED` | How many drones one player may have out at once |
| `LINK_RANGE` / `SIGNAL_RANGE_PER_TIER` | How far the pilot may stray before the link drops, and how much each signal module adds |
| `SCAN_RANGE` / `SCAN_FOV` / `PING_RADIUS` | Detection cone, and the radius inside which angle does not matter |
| `THROW_SCAN_FOV` / `THROW_PING_RADIUS` | The wider pair a tumbling thrown drone uses |
| `GLOW_DURATION` / `SCAN_INTERVAL` | How long a mark lasts / how often it refreshes |
| `FLIGHT_SPEED` / `PLAYER_SPRINT_SPEED` / `SPEED_MODULE_MULTIPLIER` | Blocks per tick while piloted, and the absolute ceiling the speed module unlocks |
| `BATTERY_FULL` / `BATTERY_DRAIN_TICKS` / `RECALL_TICKS` | Charge units in a full pack, ticks per unit, and how long a recall takes |
| `CHEST_MARKING` / `CHEST_RANGE` / `CHEST_INTERVAL` / `CHEST_MAX_MARKERS` | Container sweep: master switch, radius, tick interval, marker budget |
| `CHEST_MIN_EXPOSED_FACES` / `CHEST_LINE_OF_SIGHT` / `CHEST_MAX_WALL_LAYERS` | How buried a container may be before it stops counting |
| `ALERT_ENABLED` / `ALERT_RANGE` / `ALERT_INTERVAL` | Threat sweep: master switch, radius around the owner, tick interval |
| `ALERT_WARN_DISTANCE` / `ALERT_CRITICAL_DISTANCE` | Where the threat reading starts climbing, and where it is full |
| `ALERT_EXCLUDE_NEUTRAL` / `ALERT_LINE_OF_SIGHT` | Whether neutral mobs count, and whether a wall hides a threat |
| `RECON_BOOST_MULTIPLIER` | How much the recon boost stretches both the cone and the reach |
| `DETECT_ALERT_ENABLED` / `DETECT_ALERT_INTERVAL` | Whether a marked player is told, and the per player throttle |
| `INTERACT_RANGE` | How far the drone can reach when the pilot right clicks |
| `THROW_SPEED` / `THROW_TICKS` | Thrown drone launch speed and ballistic phase length |
| `MAX_HEALTH` | Hit points (10 = five hearts) |
| `HURT_INVULNERABLE_TICKS` | Immunity window after each hit |
| `BANK_GAIN` / `MAX_BANK` / `BANK_SMOOTHING` | How hard the drone leans into its turns |
| `STATIC_ONSET` / `STATIC_STEP` | Where the snow starts and how many blocks each grade lasts |
| `MAX_STEP` | Hard cap on a single client reported move, in blocks |
| `CHARGE_SPEED` / `CHARGE_MAX_TICKS` | How fast a run flies, and how long it may run before it gives up |
| `CHARGE_CONE` / `CHARGE_CONE_SLACK` | How far the pilot may bend the run off its axis, and the wider envelope the server allows |
| `CHARGE_DAMAGE` / `CHARGE_EXPLOSION_RADIUS` / `CHARGE_CRATER` | Blast damage, who it reaches, and how much it breaks |
| `CHARGE_COOLDOWN_TICKS` / `CHARGE_STATIC_TICKS` | How often the warhead may be re-armed, and how long the snow lasts after it goes off |
| `CHARGE_ECHO_CHASE` | How much of the gap to the server's position the pilot's client closes per tick |

The module bit flags and the prerequisite rule are in `item/DroneModules` and
`item/DroneModuleItem`. Adding a ninth module is a new bit plus a prerequisite — nothing else has
to change. One thing does have to be watched: `ReconDroneEntity#setModules` masks the synced bits
against `DroneModules.ALL`, so a new flag that is not added there gets quietly stripped the moment
the drone is deployed, and the symptom is a board that is fitted and simply does nothing.

The momentum lives in `client/DroneController` (`FLIGHT_ACCEL`, `FLIGHT_DECEL`). Raising
`FLIGHT_DECEL` makes stops snappier, lowering it makes the drone coast further. The charge aim
damping and the FOV kick are there too: `CHARGE_AIM_TAU` is how long the crosshair takes to catch
up with the mouse, `CHARGE_FOV_GAIN` is how much wider the view goes and `CHARGE_FOV_EASE` is how
many frames it takes to get there. The speed streaks share that ramp and their shape lives in
`client/DroneHud` (`STREAK_COUNT`, `STREAK_INNER`) — raise the count for a denser rush, raise the
inner radius to keep them off the middle of the screen.

The roller is in the same file: `ROLL_TICKS` is how long a tapped revolution takes,
`ROLL_COOLDOWN_TICKS` is the gap between taps, `ROLLER_RECOVER_TICKS` is how long the forced unwind
back to level takes, `ROLLER_TURNS_PER_SECOND` is the speed while held, and
`ROLLER_DASH_SPEED_GAIN` / `ROLLER_FOV_GAIN` are the dash and the view pull. `ROLL_CAMERA_SHARE` is
the one knob that makes the roller less dizzying: drop it and the camera follows only part of the
rotation, so the roll is still happening but you are not strapped to it.

The strength of the signal defocus is `MAX_RADIUS` in `client/DroneSignal` — the widest blur, in
pixels, at full signal loss. The pass schedule that turns it into a smooth falloff (three
horizontal/vertical pairs at 1.0, 0.5 and 0.25) lives in
`assets/watchcraft/shaders/post/drone_link.json`. The screen-space half of the effect is in
`client/DroneHud#drawStatic` and `#drawVignette`; if the picture ever looks milky rather than
merely degraded, the veil ceiling (`0x50`) is the first thing to lower.

The LCD filter's ingredients are the constants at the top of
`assets/watchcraft/shaders/program/lcd.fsh` — `GRID` (the black matrix), `MASK` (the subpixel
stripes) and `STRIPE_PERIOD` (how wide a subpixel is), `FRINGE`, `SOFTEN`, `BAND_WIDTH` / `BAND`,
`VIGNETTE`, `BEZEL` / `BEZEL_FRACTION`, `TINT` / `LIFT` and `FLICKER`. Those are the *ratios*
between the parts; `lcd.strength` is the one weight on all of them at once and `lcd.pixelPitch` is
how big a cell is (0 follows the resolution — see `PITCH_PER_HEIGHT`, `PITCH_MIN` and `PITCH_MAX`
in `tools/preview_lcd.py`, which mirrors it). If the picture ever looks sprinkled with colour,
`MASK` is the first one to lower; if it reads as murky rather than as screened, lower `VIGNETTE` or
`BEZEL`. The rolling band's speed is `DroneLcd.BAND_PERIOD_SECONDS`, and it is the one quantity that
has to be a Java constant rather than a shader one — the chain hands the shader a `Time` uniform
that wraps once a second, which is both too fast for a band and discontinuous at the wrap.

Three rules in that file are easy to break by accident, and all three were broken once already.
The matrix has to be **one device pixel** wide, which is why its edge is `1 - 1/pitch` of a cell
rather than a fixed fraction — widening it with the cell turns the panel into blinds. The stripe
period has to be **independent of the cell**, or the three stripes only balance when the cell is a
multiple of three wide. And both the matrix and the mask divide their own mean gain back out; drop
either and the picture silently loses brightness, or a channel.

The detonation's radial blur is split into two numbers for a reason worth knowing. `radial_blur.fsh`
reads `Strength * BlurAmount`: `Strength` is a **per-pass constant** written in that pass's own entry
in `drone_link.json` (1.0 for the first pass, 0.5 for the second), and `BlurAmount` is the single
**per-frame** value Java writes. It has to work that way because `PostChain#setUniform` applies a
uniform to **every pass that declares it** — there is no way to set one pass alone, so two passes
that want different amounts need two names, and the per-pass one has to come from the JSON.

The turn lean's *direction* is a single constant, `DroneRenderer.BANK_SIGN`. If the drone ever
looks like it is banking out of its turns rather than into them, flip that and nothing else.

Which blocks a right click may touch is a whitelist in `ModNetwork#interact` — add another
`instanceof` there to widen it.

Door sounds are echoed straight to the pilot by `ModNetwork#echoDoorSound`, unconditionally.
This is not a range fix: `DoorBlock#playSound` passes the player to `Level#playSound` as the
*except* argument, and that overload treats a Player as a recipient to exclude, so the pilot is
filtered out of the vanilla broadcast no matter how close they stand. Opening a door from the
cockpit is silent by construction.

Which keys are swallowed lives in `DroneController#swallowUiKeys`, called from
`ClientTickEvent.Pre`. That event fires at the head of `Minecraft#tick`, ahead of the vanilla
`handleKeybinds`, which is the only point early enough to work — doing it from
`ClientTickEvent.Post` would be too late, the screen would already be open.

## Docs

- [`docs/detailed-model-obj-plan.md`](docs/detailed-model-obj-plan.md) — the full modelling spec:
  every part, its pivot, its face budget and the source line that proves the API it relies on.
- [`docs/model-and-animation.md`](docs/model-and-animation.md) — how the rig works and what is
  still missing.
- [`docs/obj-modelling-brief.md`](docs/obj-modelling-brief.md) — the conventions a new part has to
  follow, and why parts interpenetrate instead of abutting.
- `docs/drone-preview.png`, `docs/drone-uv-layout.png` — the model and its texture atlas, both
  regenerable with `python tools/preview_mesh.py`.

## License

MIT — see [LICENSE](LICENSE).
