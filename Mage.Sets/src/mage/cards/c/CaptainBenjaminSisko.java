package mage.cards.c;

import mage.MageInt;
import mage.abilities.Ability;
import mage.abilities.common.SimpleActivatedAbility;
import mage.abilities.costs.common.TapSourceCost;
import mage.abilities.costs.mana.ManaCostsImpl;
import mage.abilities.dynamicvalue.DynamicValue;
import mage.abilities.effects.Effect;
import mage.abilities.effects.OneShotEffect;
import mage.abilities.effects.common.CreateTokenEffect;
import mage.abilities.triggers.BeginningOfEndStepTriggeredAbility;
import mage.cards.Cards;
import mage.cards.CardsImpl;
import mage.cards.CardImpl;
import mage.cards.CardSetInfo;
import mage.constants.CardType;
import mage.constants.ComparisonType;
import mage.constants.Outcome;
import mage.constants.SubType;
import mage.constants.SuperType;
import mage.constants.Zone;
import mage.filter.FilterCard;
import mage.filter.common.FilterControlledPermanent;
import mage.filter.predicate.mageobject.ManaValuePredicate;
import mage.game.Game;
import mage.game.permanent.token.OfficerToken;
import mage.players.Player;
import mage.util.CardUtil;
import mage.watchers.common.FacedDilemmaWatcher;

import java.util.UUID;

public final class CaptainBenjaminSisko extends CardImpl {

    public CaptainBenjaminSisko(UUID ownerId, CardSetInfo setInfo) {
        super(ownerId, setInfo, new CardType[]{CardType.CREATURE}, "{1}{W}");

        this.supertype.add(SuperType.LEGENDARY);
        this.subtype.add(SubType.HUMAN);
        this.subtype.add(SubType.OFFICER);
        this.power = new MageInt(2);
        this.toughness = new MageInt(2);

        // At the beginning of your end step, create a 1/1 red Officer creature token for each dilemma you've faced this turn.
        Ability ability = new BeginningOfEndStepTriggeredAbility(
                new CreateTokenEffect(new OfficerToken(), CaptainBenjaminSiskoDilemmas.instance)
                        .setText("create a 1/1 red Officer creature token for each dilemma you've faced this turn")
        );
        ability.addWatcher(new FacedDilemmaWatcher());
        this.addAbility(ability);

        // {2}{W}{U}{B}{R}{G}, {T}: Exile the top five cards of your library. You may cast any number of spells with
        // mana value less than or equal to the number of Officers you control from among those cards without paying
        // their mana costs.
        Ability activated = new SimpleActivatedAbility(new CaptainBenjaminSiskoEffect(), new ManaCostsImpl<>("{2}{W}{U}{B}{R}{G}"));
        activated.addCost(new TapSourceCost());
        this.addAbility(activated);
    }

    private CaptainBenjaminSisko(final CaptainBenjaminSisko card) {
        super(card);
    }

    @Override
    public CaptainBenjaminSisko copy() {
        return new CaptainBenjaminSisko(this);
    }
}

enum CaptainBenjaminSiskoDilemmas implements DynamicValue {
    instance;

    @Override
    public int calculate(Game game, Ability sourceAbility, Effect effect) {
        return FacedDilemmaWatcher.getCount(sourceAbility.getControllerId(), game);
    }

    @Override
    public CaptainBenjaminSiskoDilemmas copy() {
        return this;
    }

    @Override
    public String getMessage() {
        return "dilemma you've faced this turn";
    }

    @Override
    public String toString() {
        return "1";
    }
}

class CaptainBenjaminSiskoEffect extends OneShotEffect {

    private static final FilterControlledPermanent officers = new FilterControlledPermanent(SubType.OFFICER, "Officers you control");

    CaptainBenjaminSiskoEffect() {
        super(Outcome.PlayForFree);
        staticText = "exile the top five cards of your library. You may cast any number of spells with mana value "
                + "less than or equal to the number of Officers you control from among those cards without paying "
                + "their mana costs";
    }

    private CaptainBenjaminSiskoEffect(final CaptainBenjaminSiskoEffect effect) {
        super(effect);
    }

    @Override
    public CaptainBenjaminSiskoEffect copy() {
        return new CaptainBenjaminSiskoEffect(this);
    }

    @Override
    public boolean apply(Game game, Ability source) {
        Player controller = game.getPlayer(source.getControllerId());
        if (controller == null) {
            return false;
        }
        Cards cards = new CardsImpl(controller.getLibrary().getTopCards(game, 5));
        if (cards.isEmpty()) {
            return false;
        }
        controller.moveCards(cards, Zone.EXILED, source, game);
        cards.retainZone(Zone.EXILED, game);
        int count = game.getBattlefield().count(officers, source.getControllerId(), source, game);
        FilterCard filter = new FilterCard("spells with mana value " + count + " or less");
        filter.add(new ManaValuePredicate(ComparisonType.FEWER_THAN, count + 1));
        CardUtil.castMultipleWithAttributeForFree(controller, source, game, cards, filter);
        return true;
    }
}
