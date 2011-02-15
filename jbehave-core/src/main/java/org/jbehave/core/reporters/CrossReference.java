package org.jbehave.core.reporters;

import com.thoughtworks.xstream.XStream;
import com.thoughtworks.xstream.io.json.JsonHierarchicalStreamDriver;
import org.jbehave.core.io.StoryLocation;
import org.jbehave.core.model.ExamplesTable;
import org.jbehave.core.model.Meta;
import org.jbehave.core.model.Narrative;
import org.jbehave.core.model.Scenario;
import org.jbehave.core.model.Story;
import org.jbehave.core.steps.StepMonitor;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class CrossReference extends Format {

    private String currentStoryPath;
    private String currentScenarioTitle;
    private List<Story> stories = new ArrayList<Story>();
    private Map<String, List<StepMatch>> stepMatches = new HashMap<String, List<StepMatch>>();
    private StepMonitor stepMonitor = new XrefStepMonitor();
    private Set<String> failingStories = new HashSet<String>();

    public CrossReference() {
        this("XREF");
    }

    public CrossReference(String name) {
        super(name);
    }

    public StepMonitor getStepMonitor() {
        return stepMonitor;
    }

    public void outputToFiles(StoryReporterBuilder storyReporterBuilder) {
        XrefRoot root = createXRefRootNode(storyReporterBuilder, stepMatches, stories, failingStories);
        outputFile("xref.xml", new XStream(), root, storyReporterBuilder);
        outputFile("xref.json", new XStream(new JsonHierarchicalStreamDriver()), root, storyReporterBuilder);
    }

    protected final XrefRoot createXRefRootNode(StoryReporterBuilder storyReporterBuilder, Map<String, List<StepMatch>> stepMatches, List<Story> stories, Set<String> failingStories) {
        XrefRoot xrefRoot = makeXRefRootNode(stepMatches);
        xrefRoot.processStories(stories, storyReporterBuilder, failingStories);
        return xrefRoot;
    }

    protected XrefRoot makeXRefRootNode(Map<String, List<StepMatch>> stepMatches) {
        return new XrefRoot(stepMatches);
    }

    private void outputFile(String name, XStream xstream, XrefRoot root, StoryReporterBuilder storyReporterBuilder){
        File outputDir = new File(storyReporterBuilder.outputDirectory(), "view");
        outputDir.mkdirs();
        try {
            Writer writer = makeWriter(new File(outputDir, name));
            writer.write(configure(xstream).toXML(root));
            writer.flush();
            writer.close();
        } catch (IOException e) {
            throw new XrefOutputFailed(name, e);
        }

    }

    @SuppressWarnings("serial")
    public static class XrefOutputFailed extends RuntimeException {

        public XrefOutputFailed(String name, Throwable cause) {
            super(name, cause);
        }

    }

    protected Writer makeWriter(File file) throws IOException {
        return new FileWriter(file);
    }

    private XStream configure(XStream xstream) {
        xstream.setMode(XStream.NO_REFERENCES);
        xStreamAliasForXRefRoot(xstream);
        xStreamAliasForXRefStory(xstream);
        xstream.alias("stepMatch", StepMatch.class);
        xstream.omitField(ExamplesTable.class, "parameterConverters");
        xstream.omitField(ExamplesTable.class, "defaults");
        return xstream;
    }

    protected void xStreamAliasForXRefStory(XStream xstream) {
        xstream.alias("story", XrefStory.class);
    }

    protected void xStreamAliasForXRefRoot(XStream xstream) {
        xstream.alias("xref", XrefRoot.class);
    }

    @Override
    public StoryReporter createStoryReporter(FilePrintStreamFactory factory, StoryReporterBuilder storyReporterBuilder) {
        return new NullStoryReporter() {

            @Override
            public void beforeStory(Story story, boolean givenStory) {
                stories.add(story);
                currentStoryPath = story.getPath();
            }

            @Override
            public void failed(String step, Throwable cause) {
                super.failed(step, cause);
                failingStories.add(currentStoryPath);
            }

            @Override
            public void beforeScenario(String title) {
                currentScenarioTitle = title;
            }
        };
    }

    private class XrefStepMonitor extends StepMonitor.NULL {
        public void stepMatchesPattern(String step, boolean matches, String pattern, Method method, Object stepsInstance) {
            if (matches) {
                List<StepMatch> val = stepMatches.get(pattern);
                if (val == null) {
                    val = new ArrayList<StepMatch>();
                    stepMatches.put(pattern, val);
                }
                val.add(new StepMatch(currentStoryPath, currentScenarioTitle, step));
            }
            super.stepMatchesPattern(step, matches, pattern, method, stepsInstance);
        }
    }

    @SuppressWarnings("unused")
    public static class XrefRoot {
        private Set<String> meta = new HashSet<String>();
        private List<XrefStory> stories = new ArrayList<XrefStory>();
        private Map<String, List<StepMatch>> stepMatches;

        public XrefRoot(Map<String, List<StepMatch>> stepMatches) {
            this.stepMatches = stepMatches;
        }

        protected void processStories(List<Story> stories, StoryReporterBuilder storyReporterBuilder, Set<String> failures) {
            for (Story story : stories) {
                this.stories.add(createXRefStoryNode(storyReporterBuilder, story, !failures.contains(story.getPath()), this));
            }
        }

        /*
         * Ensure that XrefStory is instantiated completely, before secondary methods are invoked (or overridden)
         */
        protected final XrefStory createXRefStoryNode(StoryReporterBuilder storyReporterBuilder, Story story, boolean passed, XrefRoot root) {
            XrefStory xrefStory = makeXRefStoryNode(storyReporterBuilder, story, passed);
            xrefStory.processMetaTags(root);
            xrefStory.processScenarios();
            return xrefStory;
        }

        /**
         * Override this is you want to add fields to the JSON.  Specifically, create a subclass of XrefStory to return.
         * @param storyReporterBuilder the story reporter builder
         * @param story the story
         * @param passed the story passed (or failed)
         * @return
         */
        protected XrefStory makeXRefStoryNode(StoryReporterBuilder storyReporterBuilder, Story story, boolean passed) {
            return new XrefStory(story, storyReporterBuilder, passed);
        }
    }

    @SuppressWarnings("unused")
    public static class XrefStory {
        private transient Story story; // don't turn into JSON.
        private String description;
        private String narrative = "";
        private String name;
        private String path;
        private String html;
        private String meta = "";
        private String scenarios = "";
        private boolean passed;

        public XrefStory(Story story, StoryReporterBuilder storyReporterBuilder, boolean passed) {
            this.story = story;
            Narrative narrative = story.getNarrative();
            if (!narrative.isEmpty()) {
                this.narrative = "In order to " + narrative.inOrderTo() + "\n" + "As a " + narrative.asA() + "\n"
                        + "I want to " + narrative.iWantTo() + "\n";
            }
            this.description = story.getDescription().asString();
            this.name = story.getName();
            this.path = story.getPath();
            this.passed = passed;
            this.html = storyReporterBuilder.pathResolver().resolveName(new StoryLocation(null, story.getPath()), "html");
        }

        protected void processScenarios() {
            for (Scenario scenario : story.getScenarios()) {
                String body = "Scenario:" + scenario.getTitle() + "\n";
                List<String> steps = scenario.getSteps();
                for (String step : steps) {
                    body = body + step + "\n";
                }
                scenarios = scenarios + body + "\n\n";
            }
        }

        protected void processMetaTags(XrefRoot root) {
            Meta storyMeta = story.getMeta();
            for (String next : storyMeta.getPropertyNames()) {
                String property = next + "=" + storyMeta.getProperty(next);
                addMetaProperty(property, root.meta);
                String newMeta = appendMetaProperty(property, this.meta);
                if (newMeta != null) {
                    this.meta = newMeta;
                }
            }
        }

        protected String appendMetaProperty(String property, String meta) {
            return meta + property + "\n";
        }

        protected void addMetaProperty(String property, Set<String> meta) {
            meta.add(property);
        }
    }

    @SuppressWarnings("unused")
    public static class StepMatch {
        private final String storyPath;
        private final String scenarioTitle;
        private final String step;

        public StepMatch(String storyPath, String scenarioTitle, String step) {
            this.storyPath = storyPath;
            this.scenarioTitle = scenarioTitle;
            this.step = step;
        }
    }

}
