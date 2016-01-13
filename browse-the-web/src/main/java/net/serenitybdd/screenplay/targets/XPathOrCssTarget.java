package net.serenitybdd.screenplay.targets;

import net.serenitybdd.core.pages.WebElementFacade;
import net.serenitybdd.screenplay.Actor;
import net.serenitybdd.screenplay.abilities.BrowseTheWeb;

import java.util.List;

public class XPathOrCssTarget extends Target {

    private final String cssOrXPathSelector;

    public XPathOrCssTarget(String targetElementName, String cssOrXPathSelector) {
        super(targetElementName);
        this.cssOrXPathSelector = cssOrXPathSelector;
    }

    public WebElementFacade resolveFor(Actor actor) {
        TargetResolver resolver = new TargetResolver(BrowseTheWeb.as(actor).getDriver());
        return resolver.findBy(cssOrXPathSelector);
    }

    public List<WebElementFacade> resolveAllFor(Actor actor) {
        TargetResolver resolver = new TargetResolver(BrowseTheWeb.as(actor).getDriver());
        return resolver.findAll(cssOrXPathSelector);
    }

    public XPathOrCssTarget of(String... parameters) {
        return new XPathOrCssTarget(targetElementName, instantiated(cssOrXPathSelector, parameters));
    }

    public XPathOrCssTarget called(String name) {
        return new XPathOrCssTarget(name, cssOrXPathSelector);
    }

    private String instantiated(String cssOrXPathSelector, String[] parameters) {
        return new TargetSelectorWithVariables(cssOrXPathSelector).resolvedWith(parameters);
    }
}