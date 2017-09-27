import org.apache.commons.lang3.StringUtils

// Script to generate a draft for a "translation" of a tag libary to HTL from its TLD

filename = "/cppl.xml"; // change as needed
def tld = new XmlParser().parse(getClass().getResourceAsStream(filename));

println("<!--/* ")
println("    " + tld.'short-name'.text().trim() + " : " + tld.description.text().trim())
println("    generated with generateHtlTld.groovy from " + filename)
println("*/-->")
println()
tld.tag.each { tag ->
    String tagName = tag.name.text()
    attribnames = new StringBuffer()
    tag.attribute.each { attribnames.append(", ").append(it.name.text()) }
    attribnames.delete(0, 2)
    attribassignments = new StringBuffer()
    tag.attribute.each { attribassignments.append(", ").append("${it.name.text()}=${it.name.text()}") }

    if (tag.'body-content'.text().contains("JSP")) {
        println('<template data-sly-template.start' + StringUtils.capitalize(tagName) + '="${@ ' + attribnames + '}"><!--/* ')
        println("    " + tag.description.text().trim())
        tag.attribute.each {
            println("        " + it.name.text() + ' (required=' + it.required.text() + ', '
                    + it.type.text().replace('java.lang.','') + ") "
                    + it.description.text().trim())
        }
        println(' */--><sly data-sly-use.adapter="${\'com.composum.platform.models.htl.TagHtlAdapter\' @ ')
        println("        adapterTagClass='${tag.'tag-class'.text()}'${attribassignments}}\"")
        println("        >\${adapter.doStart}</sly></template>")
        println()

        println('<template data-sly-template.end' + StringUtils.capitalize(tagName) +
                '><sly data-sly-use.adapter="${\'com.composum.platform.models.htl.TagHtlAdapter\' @ ' )
        println("        adapterTagClass='${tag.'tag-class'.text()}'}\">\${adapter.doEnd}</sly></template>")
        println()
        println()
    } else {
        println('<template data-sly-template.' + tagName + '="${@ ' + attribnames + '}"><!--/* ')
        println("    " + tag.description.text().trim())
        tag.attribute.each {
            println("        " + it.name.text() + ' (required=' + it.required.text() + ', '
                    + it.type.text().replace('java.lang.','') + ") "
                    + it.description.text().trim())
        }
        println(' */--><sly data-sly-use.adapter="${\'com.composum.platform.models.htl.TagHtlAdapter\' @ ')
        println("        adapterTagClass='${tag.'tag-class'.text()}'${attribassignments}}\"")
        println("        >\${adapter.doStartAndEnd}</sly></template>")
        println()
        println()
    }
}
