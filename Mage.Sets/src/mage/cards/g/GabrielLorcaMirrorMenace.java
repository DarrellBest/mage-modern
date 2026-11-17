package mage.cards.g;

import mage.MageInt;
import mage.abilities.common.FaceDilemmaTriggeredAbility;
import mage.abilities.costs.mana.ManaCostsImpl;
import mage.abilities.effects.common.DoIfCostPaid;
import mage.abilities.effects.common.ReturnSourceFromGraveyardToHandEffect;
import mage.abilities.keyword.MenaceAbility;
import mage.cards.CardImpl;
import mage.cards.CardSetInfo;
import mage.constants.CardType;
import mage.constants.SubType;
import mage.constants.SuperType;
import mage.constants.Zone;

import java.util.UUID;

public final class GabrielLorcaMirrorMenace extends CardImpl {

    public GabrielLorcaMirrorMenace(UUID ownerId, CardSetInfo setInfo) {
        super(ownerId, setInfo, new CardType[]{CardType.CREATURE}, "{4}{B}");

        this.supertype.add(SuperType.LEGENDARY);
        this.subtype.add(SubType.HUMAN);
        this.subtype.add(SubType.ROGUE);
        this.subtype.add(SubType.OFFICER);
        this.power = new MageInt(5);
        this.toughness = new MageInt(4);

        // Menace
        this.addAbility(new MenaceAbility());

        // Whenever you face a dilemma, you may pay {B}. If you do, return this card from your graveyard to your hand.
        this.addAbility(new FaceDilemmaTriggeredAbility(Zone.GRAVEYARD, new DoIfCostPaid(
                new ReturnSourceFromGraveyardToHandEffect().setText("return this card from your graveyard to your hand"),
                new ManaCostsImpl<>("{B}")
        ), false));
    }

    private GabrielLorcaMirrorMenace(final GabrielLorcaMirrorMenace card) {
        super(card);
    }

    @Override
    public GabrielLorcaMirrorMenace copy() {
        return new GabrielLorcaMirrorMenace(this);
    }
}
