# Pro-player gameplay principles for the Commander AI

39 checkable decision principles mined from pro players and respected Commander strategists
(Reid Duke's Level One column, Mike Flores, Sheldon Menery, Star City Games, EDHREC, Card
Kingdom, Commander's Herald, and others). Compiled 2026-08-16 from full article text, each
verified against the source, not search snippets.

Purpose: (1) grade the AI's audit logs against these to find misplays; (2) rank which
behaviours the evaluator/search/features cannot even represent yet. Each principle carries a
DETECT note describing how a violation shows up in our audit stream (PLAY/ATTACK/BLOCK/IDLE/
FEATURES/RESULT records).

Categories: threat assessment (1-5), removal discipline (6-11), sequencing (12-15), combat
math and life (16-21), mulligans (22-24), card advantage vs tempo (25-28), politics (29-33),
commander mechanics (34-37), classic amateur mistakes (38-39).

## Threat assessment

1. **Track two rankings each turn: who is closest to winning, and who sees you as their
   biggest threat.** They are often different players. DETECT: attack/removal target
   correlates with "who hit me last" rather than an independent closest-to-winning score.
   (Erik Tiernan, SCG)
2. **Re-run threat assessment every turn; never attack out of revenge.** One resolved
   permanent can flip the real threat. DETECT: 3+ consecutive turns of attacks at the same
   opponent while a different opponent's board/engines grow unchecked. (Sainio, Hipsters;
   Krell, Card Kingdom; DonSpider, Nerd Leagues)
3. **Weigh engines and trajectory over life total and raw board size.** Commander games end
   in bursts, not attrition. DETECT: threat ranking tracks board size/life instead of hand
   size, open mana, unanswered engines. (Milan, EDHREC; Gregory, Card Kingdom)
4. **Classify each player beatdown-vs-control from the current board, not deck identity.**
   DETECT: posture (attack vs hold) mismatched against relative damage output and
   interaction held. (Flores, "Who's the Beatdown?", SCG)
5. **Raise threat estimate of a player who lets others spend removal while spending none.**
   DETECT: never raising threat for a consistent non-spender who benefits from table
   answers. (Sainio, Hipsters)

## Removal and counterspell discipline

6. **Hold removal for genuine must-answer permanents** (enables a win, locks the game,
   snowballs). DETECT: removal on an isolated marginal permanent, then no answer left for a
   real threat later. (Tiernan, SCG)
7. **In Commander, do not burn hard removal on mana dorks/cheap ramp; save it for combo,
   stax, engines.** (Unlike 1v1 "bolt the bird".) DETECT: removal on a dork while a combo
   piece or engine sits unanswered. (Gregory, Card Kingdom; contrast Merriam, SCG)
8. **Before removing an attacker, check whether a profitable block answers it for free.**
   DETECT: removal cast when an available blocker traded cleanly. (Gregory, Card Kingdom)
9. **Do not spend your own removal on a symmetrical/shared problem another player is equally
   motivated to answer; if you must, break it right before your own turn.** DETECT: removal
   on shared stax when others could act; timing relative to own turn. (Sperling, Topdeck)
10. **Do not spend premium answers on already-depreciated targets** (post-ETB creature, used
    mana rock). DETECT: top removal/counter on a one-shot-value target, combo piece
    unanswered later. (Gregory; Kaylani, Cardsrealm)
11. **Bait counterspells with a lesser threat before the real payoff; tapped-out
    counter-holding is no protection.** DETECT: leading with the strong spell into open
    mana with a weaker probe available; blown out at end-of-turn while "holding" a counter
    tapped out. (Jim Davis, CoolStuffInc; Blinebry, EDHREC)

## Sequencing

12. **Cast flexible spells at the latest safe moment, ideally after the dangerous opponent
    taps out.** DETECT: flexible spell cast in own main while an opponent held untapped
    interaction mana, no bait rationale. (Reid Duke, Level One)
13. **Play the land in second main unless the mana is needed pre-combat.** DETECT: land in
    first main with no pre-combat use of it. (Sheerin, Mythic Mindset)
14. **Play around the single most likely/impactful answer, not every hypothetical.**
    DETECT: real tempo paid hedging a low-probability card. (Klomparens, SCG)
15. **Spend interaction on lesser threats during otherwise-idle turns rather than hoarding.**
    DETECT: interaction held through a turn with no proactive play, then spent reactively at
    tempo cost later. (Handy, SCG)

## Combat math and life as a resource

16. **Chump-block only to stop lethal/near-lethal or buy a specifically-needed turn.**
    DETECT: chump at high life against small damage with a utility creature thrown away.
    (Wizards, Level One)
17. **Judge damage by what it constrains, not per-point; the only meaningful life total is
    0.** DETECT: willingness to take damage does not scale with life buffer. (Reid Duke)
18. **At 40 life, 3-5 point swings are near-noise; reserve caution for near-lethal turns.**
    DETECT: bad trade made purely to save ~3-5 life at 25-30+. (FitzSimons, Commander's
    Herald)
19. **Take available even/favorable trades; do not stall symmetric combats indefinitely.**
    DETECT: declining the same even trade repeatedly, then losing to a drawn trick.
    (Reid Duke)
20. **Never attack into substantial open mana without a plan; every attack needs concrete
    justification.** DETECT: attacks into open mana losing the attacker for nothing.
    (Reid Duke; Hinds/Verhey, EDHREC)
21. **Once stabilized, turn the corner: switch from pure defense to building a clock.**
    DETECT: holding back attackers for multiple turns after stabilizing with lethal
    available. (Reid Duke)

## Mulligans (Commander)

22. **Keep 2-4 lands of the right colors; 2-land hands need ramp/draw; never below 5
    cards.** DETECT: kept 0-1 land hands without support; kept 6+ land hands; mulled below
    5. (Kerghans, Draftsim; Sherwood, EDHREC)
23. **Calibrate mulligan aggression to the deck's own land count and draw density.**
    DETECT: aggression uncorrelated with deck's mana base. (Nicol, EDHREC)
24. **Evaluate hands on Curve, Color, Plan, Sequencing, Interaction — not land count
    alone.** DETECT: kept hands with dead/situational cards and no proactive plan.
    (Sherwood, EDHREC)

## Card advantage vs tempo

25. **Early: prioritize tempo, do not pass with mana unused. Late: prioritize card
    advantage.** DETECT: early idle passes with playable spells; late card-losing tempo
    plays. (Reid Duke)
26. **Judge tempo by conversion toward winning, not raw mana efficiency.** DETECT: declining
    a race-resetting sweeper on efficiency grounds while behind on the clock. (Pierce,
    Eternal Central)
27. **In a pod you own ~25% of cards in play: weight symmetrical effects (wipes, group
    draw) far above 1v1 value.** DETECT: plan leans on 1-for-1s with wipes available and
    multiple developed opponents. (WitchPHD)
28. **Fire wipes when an opponent has overextended but not yet cashed in, and only when your
    post-wipe resources beat the table's.** DETECT: wipe timing vs overextension and
    relative resources at the moment of cast/pass. (Monsen, PrintMTG; GrimDeck)

## Politics, deals, kingmaking

29. **Honor deals literally; never pure-spite plays.** DETECT: action contradicting an
    earlier deal; plays with zero own-win benefit that only deny an opponent. (Menery, SCG)
30. **Deals need specific terms, bounded duration, verifiability, exit condition.** DETECT:
    logged deal text lacking these. (Dennis, MTGEDH)
31. **Prefer open framing over binding promises; bind only for high-stakes safety.**
    DETECT: ratio of framing vs binding deals. (MagicalHacker, mtgcommander.net)
32. **Kingmaking is intent, not outcome; when truly out, make the most neutral legal play.**
    DETECT: with zero outs, choice traces to prior aggression (fine) vs unexplained spite.
    (Wicker, EDHREC; Hammond, Commander's Herald)
33. **Redirect pressure to a newly-clear leader within a turn or two — but only spend if you
    can profit, not purely for a third player's benefit.** DETECT: attack/removal shift
    latency after a leader emerges. (Amundson, EDHREC; Mauri, Quiet Speculation)

## Commander-specific mechanics

34. **Recast-through-tax economics scale with base cost: 1-3 CMC freely, 4-5 once or twice,
    6+ rarely.** DETECT: repeated recasts of a 6+ CMC commander at steep tax over deploying
    other threats. (GrimDeck)
35. **Protection investment scales inversely with recastability.** DETECT: no protection
    held/deployed for a high-CMC commander with mana available; repeated avoidable deaths.
    (Bennie Smith, EDHREC)
36. **Voltron: protection before stacking auras/equipment; power breakpoints 7/11/21 (3, 2,
    1 hits to 21).** DETECT: 2+ attachments on an unprotected commander into open removal
    mana; pumps that do not cross a breakpoint. (Geeky Domain)
37. **Commander damage is a backup win route, not the sole plan.** DETECT: whole hand
    committed to one commander-damage line with no fallback. (Gregory, Card Kingdom)

## Classic amateur mistakes

38. **Do not overextend into open wipe mana; hold some threats back.** DETECT: 3+ new
    permanents added to an already-dominant board while an opponent held wipe-shaped mana.
    (GrimDeck; PlayEDH)
39. **Do not solve problems that are not yours.** DETECT: removal/blocker/counter spent on a
    threat attacking a different player with no table-wide danger. (DonSpider, Nerd Leagues)

## Source coverage notes

Command Zone (podcast-only) and r/CompetitiveEDH were unfetchable and are deliberately
absent rather than paraphrased from memory. TCGPlayer Infinite articles were JS-only shells.
Everything cited above was read in full text.
