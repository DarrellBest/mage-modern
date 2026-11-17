package mage.cards.n;

import mage.MageInt;
import mage.abilities.Ability;
import mage.abilities.common.BecomesTappedSourceTriggeredAbility;
import mage.abilities.common.SimpleActivatedAbility;
import mage.abilities.common.SimpleStaticAbility;
import mage.abilities.costs.mana.GenericManaCost;
import mage.abilities.effects.ContinuousEffect;
import mage.abilities.effects.OneShotEffect;
import mage.abilities.effects.common.continuous.BecomesCreatureTypeTargetEffect;
import mage.abilities.effects.common.continuous.GainAbilitySourceEffect;
import mage.abilities.effects.common.continuous.PlayAdditionalLandsControllerEffect;
import mage.abilities.effects.common.continuous.SetBasePowerToughnessSourceEffect;
import mage.abilities.effects.common.ruleModifying.PlayFromGraveyardControllerEffect;
import mage.abilities.effects.keyword.SurveilEffect;
import mage.abilities.keyword.MenaceAbility;
import mage.cards.CardImpl;
import mage.cards.CardSetInfo;
import mage.constants.CardType;
import mage.constants.Duration;
import mage.constants.Outcome;
import mage.constants.SubType;
import mage.constants.SuperType;
import mage.game.Game;
import mage.game.permanent.Permanent;
import mage.target.targetpointer.FixedTarget;

import java.util.Arrays;
import java.util.UUID;

public final class NogAmbitiousCadet extends CardImpl {

    public NogAmbitiousCadet(UUID ownerId, CardSetInfo setInfo) {
        super(ownerId, setInfo, new CardType[]{CardType.CREATURE}, "{B}{G}");

        this.supertype.add(SuperType.LEGENDARY);
        this.subtype.add(SubType.FERENGI);
        this.subtype.add(SubType.ROGUE);
        this.power = new MageInt(2);
        this.toughness = new MageInt(2);

        // Menace
        this.addAbility(new MenaceAbility());

        // {1}: If Nog is a Rogue, he becomes a Ferengi Scout with base power and toughness 3/4 and gains "Whenever Nog becomes tapped, surveil 2."
        this.addAbility(new SimpleActivatedAbility(new NogAmbitiousCadetScoutEffect(), new GenericManaCost(1)));

        // {3}: If Nog is a Scout, he becomes a Ferengi Officer and gains "You may play lands from your graveyard" and "You may play an additional land on each of your turns."
        this.addAbility(new SimpleActivatedAbility(new NogAmbitiousCadetOfficerEffect(), new GenericManaCost(3)));
    }

    private NogAmbitiousCadet(final NogAmbitiousCadet card) {
        super(card);
    }

    @Override
    public NogAmbitiousCadet copy() {
        return new NogAmbitiousCadet(this);
    }

    /** Becomes exactly a Ferengi + {@code role} creature (loses its other creature types), for as long as it stays. */
    static void become(Permanent nog, SubType role, Game game, Ability source) {
        ContinuousEffect types = new BecomesCreatureTypeTargetEffect(Duration.Custom, Arrays.asList(SubType.FERENGI, role), true);
        types.setTargetPointer(new FixedTarget(nog, game));
        game.addEffect(types, source);
    }
}

class NogAmbitiousCadetScoutEffect extends OneShotEffect {

    NogAmbitiousCadetScoutEffect() {
        super(Outcome.BoostCreature);
        staticText = "if {this} is a Rogue, he becomes a Ferengi Scout with base power and toughness 3/4 and gains "
                + "\"Whenever {this} becomes tapped, surveil 2.\"";
    }

    private NogAmbitiousCadetScoutEffect(final NogAmbitiousCadetScoutEffect effect) {
        super(effect);
    }

    @Override
    public NogAmbitiousCadetScoutEffect copy() {
        return new NogAmbitiousCadetScoutEffect(this);
    }

    @Override
    public boolean apply(Game game, Ability source) {
        Permanent nog = source.getSourcePermanentIfItStillExists(game);
        if (nog == null || !nog.hasSubtype(SubType.ROGUE, game)) {
            return false;
        }
        NogAmbitiousCadet.become(nog, SubType.SCOUT, game, source);
        game.addEffect(new SetBasePowerToughnessSourceEffect(3, 4, Duration.Custom), source);
        game.addEffect(new GainAbilitySourceEffect(new BecomesTappedSourceTriggeredAbility(new SurveilEffect(2)), Duration.Custom), source);
        return true;
    }
}

class NogAmbitiousCadetOfficerEffect extends OneShotEffect {

    NogAmbitiousCadetOfficerEffect() {
        super(Outcome.BoostCreature);
        staticText = "if {this} is a Scout, he becomes a Ferengi Officer and gains \"You may play lands from your "
                + "graveyard\" and \"You may play an additional land on each of your turns.\"";
    }

    private NogAmbitiousCadetOfficerEffect(final NogAmbitiousCadetOfficerEffect effect) {
        super(effect);
    }

    @Override
    public NogAmbitiousCadetOfficerEffect copy() {
        return new NogAmbitiousCadetOfficerEffect(this);
    }

    @Override
    public boolean apply(Game game, Ability source) {
        Permanent nog = source.getSourcePermanentIfItStillExists(game);
        if (nog == null || !nog.hasSubtype(SubType.SCOUT, game)) {
            return false;
        }
        NogAmbitiousCadet.become(nog, SubType.OFFICER, game, source);
        game.addEffect(new GainAbilitySourceEffect(new SimpleStaticAbility(PlayFromGraveyardControllerEffect.playLands()), Duration.Custom), source);
        game.addEffect(new GainAbilitySourceEffect(new SimpleStaticAbility(
                new PlayAdditionalLandsControllerEffect(1, Duration.WhileOnBattlefield)), Duration.Custom), source);
        return true;
    }
}
