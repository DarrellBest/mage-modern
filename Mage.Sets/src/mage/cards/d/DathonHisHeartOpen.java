package mage.cards.d;

import mage.MageInt;
import mage.abilities.Ability;
import mage.abilities.common.ActivateAsSorceryActivatedAbility;
import mage.abilities.common.SpellCastControllerTriggeredAbility;
import mage.abilities.costs.mana.GenericManaCost;
import mage.abilities.effects.OneShotEffect;
import mage.cards.CardImpl;
import mage.cards.CardSetInfo;
import mage.choices.Choice;
import mage.choices.ChoiceImpl;
import mage.constants.CardType;
import mage.constants.Outcome;
import mage.constants.SubType;
import mage.constants.SuperType;
import mage.counters.Counter;
import mage.filter.StaticFilters;
import mage.filter.common.FilterControlledEnchantmentPermanent;
import mage.game.Game;
import mage.game.permanent.Permanent;
import mage.game.stack.Spell;
import mage.players.Player;
import mage.target.TargetPermanent;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

public final class DathonHisHeartOpen extends CardImpl {

    public DathonHisHeartOpen(UUID ownerId, CardSetInfo setInfo) {
        super(ownerId, setInfo, new CardType[]{CardType.CREATURE}, "{G}{U}{R}");

        this.supertype.add(SuperType.LEGENDARY);
        this.subtype.add(SubType.TAMARIAN);
        this.subtype.add(SubType.OFFICER);
        this.power = new MageInt(3);
        this.toughness = new MageInt(3);

        // Whenever you cast an enchantment spell, draw a card. If it's a Saga spell, Dathon deals 2 damage to each opponent.
        this.addAbility(new SpellCastControllerTriggeredAbility(new DathonHisHeartOpenDrawEffect(),
                StaticFilters.FILTER_SPELL_AN_ENCHANTMENT, false, mage.constants.SetTargetPointer.SPELL));

        // {2}: Choose a counter on target enchantment you control. Put another counter of that kind on it. Activate only as a sorcery.
        Ability ability = new ActivateAsSorceryActivatedAbility(new DathonHisHeartOpenCounterEffect(), new GenericManaCost(2));
        ability.addTarget(new TargetPermanent(new FilterControlledEnchantmentPermanent("enchantment you control")));
        this.addAbility(ability);
    }

    private DathonHisHeartOpen(final DathonHisHeartOpen card) {
        super(card);
    }

    @Override
    public DathonHisHeartOpen copy() {
        return new DathonHisHeartOpen(this);
    }
}

class DathonHisHeartOpenDrawEffect extends OneShotEffect {

    DathonHisHeartOpenDrawEffect() {
        super(Outcome.DrawCard);
        staticText = "draw a card. If it's a Saga spell, {this} deals 2 damage to each opponent";
    }

    private DathonHisHeartOpenDrawEffect(final DathonHisHeartOpenDrawEffect effect) {
        super(effect);
    }

    @Override
    public DathonHisHeartOpenDrawEffect copy() {
        return new DathonHisHeartOpenDrawEffect(this);
    }

    @Override
    public boolean apply(Game game, Ability source) {
        Player controller = game.getPlayer(source.getControllerId());
        if (controller == null) {
            return false;
        }
        controller.drawCards(1, source, game);
        Spell spell = game.getSpellOrLKIStack(getTargetPointer().getFirst(game, source));
        if (spell != null && spell.hasSubtype(SubType.SAGA, game)) {
            for (UUID opponentId : game.getOpponents(source.getControllerId())) {
                Player opponent = game.getPlayer(opponentId);
                if (opponent != null) {
                    opponent.damage(2, source.getSourceId(), source, game);
                }
            }
        }
        return true;
    }
}

class DathonHisHeartOpenCounterEffect extends OneShotEffect {

    DathonHisHeartOpenCounterEffect() {
        super(Outcome.BoostCreature);
        staticText = "choose a counter on target enchantment you control. Put another counter of that kind on it";
    }

    private DathonHisHeartOpenCounterEffect(final DathonHisHeartOpenCounterEffect effect) {
        super(effect);
    }

    @Override
    public DathonHisHeartOpenCounterEffect copy() {
        return new DathonHisHeartOpenCounterEffect(this);
    }

    @Override
    public boolean apply(Game game, Ability source) {
        Player controller = game.getPlayer(source.getControllerId());
        Permanent enchantment = game.getPermanent(getTargetPointer().getFirst(game, source));
        if (controller == null || enchantment == null) {
            return false;
        }
        Set<String> kinds = new LinkedHashSet<>();
        for (Counter counter : enchantment.getCounters(game).values()) {
            if (counter.getCount() > 0) {
                kinds.add(counter.getName());
            }
        }
        if (kinds.isEmpty()) {
            return false;
        }
        String kind = kinds.iterator().next();
        if (kinds.size() > 1) {
            Choice choice = new ChoiceImpl(true);
            choice.setMessage("Choose a kind of counter");
            choice.setChoices(kinds);
            controller.choose(Outcome.BoostCreature, choice, game);
            if (choice.getChoice() != null) {
                kind = choice.getChoice();
            }
        }
        enchantment.addCounters(mage.counters.CounterType.findByName(kind).createInstance(), source.getControllerId(), source, game);
        return true;
    }
}
