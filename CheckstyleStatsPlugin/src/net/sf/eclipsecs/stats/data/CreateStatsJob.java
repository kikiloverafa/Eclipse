//============================================================================
//
// Copyright (C) 2002-2006  David Schneider, Lars K�dderitzsch, Fabrice Bellingard
//
// This library is free software; you can redistribute it and/or
// modify it under the terms of the GNU Lesser General Public
// License as published by the Free Software Foundation; either
// version 2.1 of the License, or (at your option) any later version.
//
// This library is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
// Lesser General Public License for more details.
//
// You should have received a copy of the GNU Lesser General Public
// License along with this library; if not, write to the Free Software
// Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
//
//============================================================================

package net.sf.eclipsecs.stats.data;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.MissingResourceException;
import java.util.ResourceBundle;
import java.util.regex.Pattern;

import net.sf.eclipsecs.stats.Messages;
import net.sf.eclipsecs.stats.views.internal.CheckstyleMarkerFilter;

import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;

import com.atlassw.tools.eclipse.checkstyle.CheckstylePlugin;
import com.atlassw.tools.eclipse.checkstyle.builder.CheckstyleMarker;
import com.atlassw.tools.eclipse.checkstyle.config.meta.MetadataFactory;
import com.atlassw.tools.eclipse.checkstyle.config.meta.RuleMetadata;
import com.atlassw.tools.eclipse.checkstyle.util.CheckstyleLog;
import com.atlassw.tools.eclipse.checkstyle.util.CheckstylePluginException;
import com.atlassw.tools.eclipse.checkstyle.util.CustomLibrariesClassLoader;

/**
 * Job implementation that builds the data objects for the statistic views.
 * 
 * @author Lars K�dderitzsch
 */
public class CreateStatsJob extends Job
{

    //
    // constants
    //

    /** Regexp to find {0}-like strings. */
    private static final Pattern REGEXP_HOLES = Pattern.compile("\\{[0-9]+(\\S)*\\}"); //$NON-NLS-1$

    /** Regexp to find suites of ' character. */
    private static final Pattern REGEXP_QUOTE = Pattern.compile("'+"); //$NON-NLS-1$

    /** The filter to analyze. */
    private CheckstyleMarkerFilter mFilter;

    /** The statistics data object. */
    private Stats mStats = null;

    /** The job family this job belongs to. */
    private final String mFamily;

    /**
     * Creates the job.
     * 
     * @param filter the marker filter to analyze
     */
    public CreateStatsJob(CheckstyleMarkerFilter filter, String family)
    {
        super(Messages.CreateStatsJob_msgAnalyzeMarkers);
        mFilter = (CheckstyleMarkerFilter) filter.clone();
        mFamily = family;
    }

    public boolean shouldSchedule()
    {

        Job[] similarJobs = getJobManager().find(mFamily);
        return similarJobs.length == 0;
    }

    public boolean belongsTo(Object family)
    {
        return mFamily != null && mFamily.equals(family);
    }

    /**
     * @see InternalJob#run(org.eclipse.core.runtime.IProgressMonitor)
     */
    protected IStatus run(IProgressMonitor monitor)
    {
        try
        {

            int wholeAmountOfMarkers = ResourcesPlugin.getWorkspace().getRoot().findMarkers(
                    CheckstyleMarker.MARKER_ID, true, IResource.DEPTH_INFINITE).length;

            IMarker[] markers = mFilter.findMarkers(monitor);

            Map markerStats = new HashMap();

            for (int i = 0, size = markers.length; i < size; i++)
            {

                String message = null;
                try
                {
                    message = getUnlocalizedMessage(markers[i]);
                    message = cleanMessage(message);
                }
                catch (CoreException e)
                {
                    CheckstyleLog.log(e, Messages.CreateStatsJob_errorAnalyzingMarkers);
                }

                // check that the message is not empty
                if (message == null || message.trim().length() == 0)
                {
                    // cela ne devrait pas arriver, mais bon, on laisse faire
                    CheckstyleLog.log(null, Messages.CreateStatsJob_markerMessageShouldntBeEmpty);
                    continue;
                }

                // puis on recherche
                MarkerStat stat = (MarkerStat) markerStats.get(message);
                if (stat == null)
                {
                    // 1ere fois qu'on rencontre un marqueur de ce type
                    MarkerStat newMarkerStat = new MarkerStat(message);
                    newMarkerStat.addMarker(markers[i]);
                    markerStats.put(newMarkerStat.getIdentifiant(), newMarkerStat);
                }
                else
                {
                    // on augmente juste le nombre d'occurence
                    stat.addMarker(markers[i]);
                }
            }

            mStats = new Stats(markerStats.values(), markers.length, wholeAmountOfMarkers);
        }
        catch (CoreException e)
        {
            return new Status(IStatus.ERROR, CheckstylePlugin.PLUGIN_ID, IStatus.OK,
                    Messages.CreateStatsJob_errorAnalyzingMarkers, e);
        }

        return Status.OK_STATUS;
    }

    /**
     * Returns the statistics data compiled by the job.
     * 
     * @return the statistics data
     */
    public Stats getStats()
    {
        return mStats;
    }

    /**
     * Returns.
     * 
     * @param aClassName
     * @param key
     * @return
     */
    private static String getMessageBundle(String aClassName, String key)
    {
        int endIndex = aClassName.lastIndexOf('.');
        String messages = "messages"; //$NON-NLS-1$
        if (endIndex >= 0)
        {
            String packageName = aClassName.substring(0, endIndex);
            messages = packageName + "." + messages; //$NON-NLS-1$
        }
        ResourceBundle resourceBundle;
        try
        {
            resourceBundle = ResourceBundle.getBundle(messages, CheckstylePlugin
                    .getPlatformLocale(), CustomLibrariesClassLoader.get());
        }
        catch (CheckstylePluginException e)
        {
            throw new RuntimeException(e);
        }
        return resourceBundle.getString(key);
    }

    public static String getUnlocalizedMessage(IMarker marker) throws CoreException
    {
        String key = (String) marker.getAttribute(CheckstyleMarker.MESSAGE_KEY);
        RuleMetadata ruleMetadata = MetadataFactory.getRuleMetadata((String) marker
                .getAttribute(CheckstyleMarker.MODULE_NAME));

        // for some unknown reason there is no metadata or key
        if (key == null || ruleMetadata == null)
        {
            // return the actual message
            return (String) marker.getAttribute(IMarker.MESSAGE);
        }

        List namesToCheck = new ArrayList();
        namesToCheck.add(ruleMetadata.getInternalName());
        namesToCheck.addAll(ruleMetadata.getAlternativeNames());

        for (Iterator iter = namesToCheck.iterator(); iter.hasNext();)
        {
            String checker = (String) iter.next();
            try
            {
                String message = getMessageBundle(checker, key);
                return message;
            }
            catch (MissingResourceException e)
            {
                // let's continue to check the other alternative names
            }
        }

        // none was found: return the current message
        return (String) marker.getAttribute(IMarker.MESSAGE);
    }

    /**
     * Cleans the unlocalized message so that it is more readable.
     * 
     * @param message : the message to clean
     * @return the cleaned message
     */
    public static String cleanMessage(String message)
    {
        // replacements
        String finalMessage = REGEXP_HOLES.matcher(message).replaceAll("X"); //$NON-NLS-1$
        finalMessage = REGEXP_QUOTE.matcher(finalMessage).replaceAll("'"); //$NON-NLS-1$

        return finalMessage;
    }
}
