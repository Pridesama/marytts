/**
 * Copyright 2010 DFKI GmbH.
 * All Rights Reserved.  Use is subject to license terms.
 *
 * This file is part of MARY TTS.
 *
 * MARY TTS is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, version 3 of the License.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 */

package marytts.modules;

import java.io.File;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.w3c.dom.DOMException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.traversal.NodeIterator;
import org.w3c.dom.traversal.TreeWalker;

import marytts.datatypes.MaryData;
import marytts.datatypes.MaryDataType;
import marytts.datatypes.MaryXML;

import marytts.features.FeatureProcessorManager;
import marytts.features.FeatureRegistry;

import marytts.modules.acoustic.Model;
import marytts.modules.phonemiser.Allophone;
import marytts.modules.phonemiser.AllophoneSet;
import marytts.modules.synthesis.Voice;

import marytts.server.MaryProperties;

import marytts.unitselection.select.UnitSelector;

import marytts.util.MaryUtils;
import marytts.util.dom.MaryDomUtils;

/**
 * Predict duration and F0 using CARTs or other models
 * 
 * @author steiner
 * 
 */
public class AcousticModeller extends InternalModule {

    private Map<String, List<Element>> elementLists;

    // three constructors adapted from DummyAllophones2AcoustParams (used if this is in modules.classes.list):

    public AcousticModeller() {
        this((Locale) null);
    }

    /**
     * Constructor to be called with instantiated objects.
     * 
     * @param locale
     */
    public AcousticModeller(String locale) {
        this(MaryUtils.string2locale(locale));
    }

    /**
     * Constructor to be called with instantiated objects.
     * 
     * @param locale
     */
    public AcousticModeller(Locale locale) {
        super("AcousticModeller", MaryDataType.ALLOPHONES, MaryDataType.ACOUSTPARAMS, locale);
    }

    // three constructors adapted from CARTF0Modeller (used if this is in a voice's preferredModules):

    /**
     * Constructor which can be directly called from init info in the config file. This constructor will use the registered
     * feature processor manager for the given locale.
     * 
     * @param locale
     *            a locale string, e.g. "en"
     * @param propertyPrefix
     *            the prefix to be used when looking up entries in the config files, e.g. "english.duration"
     * @throws Exception
     */
    public AcousticModeller(String locale, String propertyPrefix) throws Exception {
        this(MaryUtils.string2locale(locale), propertyPrefix, FeatureRegistry.getFeatureProcessorManager(MaryUtils
                .string2locale(locale)));
    }

    /**
     * Constructor which can be directly called from init info in the config file. Different languages can call this code with
     * different settings.
     * 
     * @param locale
     *            a locale string, e.g. "en"
     * @param propertyPrefix
     *            the prefix to be used when looking up entries in the config files, e.g. "english.f0"
     * @param featprocClass
     *            a package name for an instance of FeatureProcessorManager, e.g. "marytts.language.en.FeatureProcessorManager"
     * @throws Exception
     */
    public AcousticModeller(String locale, String propertyPrefix, String featprocClassInfo) throws Exception {
        this(MaryUtils.string2locale(locale), propertyPrefix, (FeatureProcessorManager) MaryUtils
                .instantiateObject(featprocClassInfo));
    }

    /**
     * Constructor to be called with instantiated objects.
     * 
     * @param locale
     * @param propertyPrefix
     *            the prefix to be used when looking up entries in the config files, e.g. "english.f0"
     * @param featureProcessorManager
     *            the manager to use when looking up feature processors.
     */
    protected AcousticModeller(Locale locale, String propertyPrefix, FeatureProcessorManager featureProcessorManager) {
        super("AcousticModeller", MaryDataType.ALLOPHONES, MaryDataType.ACOUSTPARAMS, locale);
    }

    public MaryData process(MaryData d) {
        Document doc = d.getDocument();
        MaryData output = new MaryData(outputType(), d.getLocale());

        // cascaded voice identification:
        Element voiceElement = (Element) doc.getElementsByTagName(MaryXML.VOICE).item(0);
        Voice voice = Voice.getVoice(voiceElement);
        if (voice == null) {
            voice = d.getDefaultVoice();
        }
        if (voice == null) {
            // Determine Locale in order to use default voice
            Locale locale = MaryUtils.string2locale(doc.getDocumentElement().getAttribute("xml:lang"));
            voice = Voice.getDefaultVoice(locale);
        }

        // get models from voice, if they are defined:
        Map<String, Model> models = voice.getAcousticModels();
        if (models == null) {
            // unless voice provides suitable models, pass out unmodified MaryXML, just like DummyAllophones2AcoustParams:
            logger.debug("No acoustic models defined in " + voice.getName() + "; could not process!");
            output.setDocument(doc);
            return output;
        }

        /*
         * Actual processing below here; applies only when Voice provides appropriate models:
         */

        // parse the MaryXML Document to populate Lists of relevant Elements:
        parseDocument(doc);
       
        // unpack elementLists from Map:
        List<Element> segments = elementLists.get("segments");
        List<Element> firstVoicedSegments = elementLists.get("firstVoicedSegments");
        List<Element> firstVowels = elementLists.get("firstVowels");
        List<Element> lastVoicedSegments = elementLists.get("lastVoicedSegments");
        List<Element> boundaries = elementLists.get("boundaries");

        // apply critical Models to Elements:
        System.out.println("\nApplying DurationModel");
        voice.getDurationModel().applyTo(segments);
        
        // hack duration attributes:
        // IMPORTANT: this hack has to be done right after predict durations, 
        // because the dur value is used by the HMMs.
        hackSegmentDurations(elementLists.get("segments"));
              
        if( voice.getLeftF0Model() != null )  // if cart models were defined apply these models
        {
          // voice.getLeftF0 ... will return null if not defined
          System.out.println("\nApplying LeftF0Model");
          voice.getLeftF0Model().applyTo(firstVoicedSegments, firstVowels);
        
          System.out.println("\nApplying MidF0Model");
          voice.getMidF0Model().applyTo(firstVowels);
       
          System.out.println("\nApplying RightF0Model");
          voice.getRightF0Model().applyTo(lastVoicedSegments, firstVowels);
          
          System.out.println("\nApplying BoundaryModel");
          voice.getBoundaryModel().applyTo(boundaries);
        }

        // apply other Models, if applicable:
        Map<String, Model> otherModels = voice.getOtherModels();
        if (!otherModels.isEmpty()) {
            
            // CHECK:  we need to execute the modules in the order specified in the config file
            // for example:
            //   duration hmmF0 prosody
            // if prosody needs to change f0 it has to be set beforehand!
            
            if( otherModels.containsKey("hmmF0")){
                Model model = models.get("hmmF0");
                System.out.println("\nApplying HMMModel");  
                model.apply(elementLists.get(model.getTargetElementListName()));  
            }
            
            if( otherModels.containsKey("prosody") ) {
                Model model = models.get("prosody");
                System.out.println("\nApplying ProsodyModel");  
                model.apply(doc); 
            }
            
            
            
            /* CHECK: this is problematic, it can make the process random ???
             * for (String modelName : otherModels.keySet()) {
                Model model = models.get(modelName);
                // CHECK: with Ingmar and Sathish 
                if( modelName.contentEquals("prosody")){
                  System.out.println("\nApplying ProsodyModel");  
                  model.apply(doc); 
                }
                else{  // then it will be a hmm model
                  System.out.println("\nApplying HMMModel");  
                  model.apply(elementLists.get(model.getTargetElementListName()));
                }
                // remember, the Model constructor will apply the model to "segments" if the targetElementListName is null
            }*/
        }

        output.setDocument(doc);
        return output;
    }

    /**
     * Hack duration attributes so that <code>d</code> attribute values are in milliseconds, and add <code>end</code> attributes
     * containing the cumulative end time.
     * 
     * @param elements
     *            a List of segment Elements
     */
    private void hackSegmentDurations(List<Element> elements) {
        float cumulEndInSeconds = 0;
        for (Element segment : elements) {
            float durationInSeconds = Float.parseFloat(segment.getAttribute("d"));
            cumulEndInSeconds += durationInSeconds;

            // cumulative end time in seconds:
            String endStr = Float.toString(cumulEndInSeconds);
            segment.setAttribute("end", endStr);

            // duration rounded to milliseconds:
            String durationInMilliseconds = String.format("%.0f", (durationInSeconds * 1000));
            segment.setAttribute("d", durationInMilliseconds);
        }
    }

    /**
     * Parse the Document to populate the Lists of Elements
     * 
     * @param doc
     */
    private void parseDocument(Document doc) {

        // initialize Element Lists:
        elementLists = new HashMap<String, List<Element>>();
        List<Element> segments = new ArrayList<Element>();
        List<Element> boundaries = new ArrayList<Element>();
        List<Element> firstVoicedSegments = new ArrayList<Element>();
        List<Element> firstVowels = new ArrayList<Element>();
        List<Element> lastVoicedSegments = new ArrayList<Element>();
        List<Element> voicedSegments = new ArrayList<Element>();

        // walk over all syllables in MaryXML document:
        TreeWalker treeWalker = MaryDomUtils.createTreeWalker(doc, MaryXML.SYLLABLE, MaryXML.BOUNDARY);
        Node node;
        while ((node = treeWalker.nextNode()) != null) {
            Element element = (Element) node;

            // handle boundaries
            if (node.getNodeName().equals(MaryXML.BOUNDARY)) {
                boundaries.add(element);
                continue;
            }

            // from this point on, we should be dealing only with syllables:
            assert node.getNodeName().equals(MaryXML.SYLLABLE);

            // get AllophoneSet for syllable
            AllophoneSet allophoneSet = null; // TODO should this be here, or rather outside the loop?
            try {
                allophoneSet = AllophoneSet.determineAllophoneSet(element);
            } catch (Exception e) {
                e.printStackTrace();
            }
            assert allophoneSet != null;

            // initialize some variables:
            Element segment;
            Element firstVoicedSegment = null;
            Element firstVowel = null;
            Element lastVoicedSegment = null;

            // iterate over "ph" children of syllable:
            for (segment = MaryDomUtils.getFirstElementByTagName(node, MaryXML.PHONE); segment != null; segment = MaryDomUtils
                    .getNextOfItsKindIn(segment, element)) {

                // in passing, append segment to segments List:
                segments.add(segment);

                // get "p" attribute...
                String phone = UnitSelector.getPhoneSymbol(segment);
                // ...and get the corresponding allophone, which knows about its phonological features:
                Allophone allophone = allophoneSet.getAllophone(phone);
                if (allophone.isVoiced()) { // all and only voiced segments are potential F0 anchors
                    voicedSegments.add(segment);
                    if (firstVoicedSegment == null) {
                        firstVoicedSegment = segment;
                    }
                    if (firstVowel == null && allophone.isVowel()) {
                        firstVowel = segment;
                    }
                    lastVoicedSegment = segment; // keep overwriting this; finally it's the last voiced segment
                }
            }

            try {
                // at this point, no TBU should be null:
                assert firstVoicedSegment != null;
                assert firstVowel != null;
                assert lastVoicedSegment != null;

                // we have what we need, append to Lists:
                firstVoicedSegments.add(firstVoicedSegment);
                firstVowels.add(firstVowel);
                lastVoicedSegments.add(lastVoicedSegment);
            } catch (AssertionError e) {
                logger.debug("WARNING: could not identify F0 anchors in malformed syllable: " + element.getAttribute("ph"));
                e.printStackTrace();
            }
        }

        // pack the Element Lists into the Map:
        elementLists.put("segments", segments);
        elementLists.put("voicedSegments", voicedSegments);
        elementLists.put("firstVoicedSegments", firstVoicedSegments);
        elementLists.put("firstVowels", firstVowels);
        elementLists.put("lastVoicedSegments", lastVoicedSegments);
        elementLists.put("boundaries", boundaries);
    }

}