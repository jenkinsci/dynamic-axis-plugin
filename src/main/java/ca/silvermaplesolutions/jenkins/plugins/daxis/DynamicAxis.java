/**
 * Primary implementation of the Dynamic Axis plugin.
 */
package ca.silvermaplesolutions.jenkins.plugins.daxis;

import hudson.EnvVars;
import hudson.Extension;
import hudson.matrix.Axis;
import hudson.matrix.AxisDescriptor;
import hudson.matrix.MatrixBuild;
import hudson.model.TaskListener;
import hudson.slaves.EnvironmentVariablesNodeProperty;
import hudson.slaves.NodeProperty;
import hudson.util.FormValidation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import java.util.regex.Pattern;

import jenkins.model.Jenkins;
import net.sf.json.JSONObject;

import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;

import com.google.common.collect.Lists;
import hudson.Util;
import java.util.Arrays;
import java.util.logging.Level;
import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;

/**
 * Implements dynamic axis support through a configurable environment variable.
 * Warnings about type name colliding with a package in Eclipse can be ignored.
 * @version 1.0.0
 */
public class DynamicAxis extends Axis
{
	private static final Logger LOGGER = Logger.getLogger( DynamicAxis.class.getName() );

	private @CheckForNull String varName = "";
	private final @Nonnull List<String> axisValues = Lists.newArrayList();

	/**
	 * Always construct from an axis name and environment variable name.
	 * @param name
	 * @param varName
	 */
	@DataBoundConstructor
	public DynamicAxis( String name, String varName )
	{
		super( name, varName );
		this.varName = varName;
		axisValues.addAll(expandVariableIfPresent(getGlobalProperties()));
	}

	/**
	 * An accessor is required if referenced in the Jelly file.
	 * @return Name of the variable. Null values will be replaced 
         * by empty strings.
	 */
	public synchronized @Nonnull String getVarName()
	{
		return varName == null ? "" : varName;
	}

	/**
	 * Ensures the list has at least one default value. Jenkins doesn't seem to
	 * like empty lists returned from getValues() or rebuild().
	 */
	private void checkForDefaultValues()
	{
		if( axisValues.isEmpty() )
		{
			LOGGER.fine( "Axis values list is empty. Adding 'default' value" );    
			axisValues.add( "default" );
		}
	}

	/**
	 * Overridden to provide a default value in the event the target environment
	 * variable cannot be accessed or interpreted.
	 * @see hudson.matrix.Axis#getValues()
         * @return Cached list of axis values from the last 
         * {@link #rebuild(hudson.matrix.MatrixBuild.MatrixBuildExecution)} call
	 */
	@Override
	public synchronized @Nonnull List<String> getValues()
	{
		checkForDefaultValues();
		return axisValues;
	}

	/**
	 * Overridden to return our environment variable name.
	 * @see hudson.matrix.Axis#getValueString()
         * @return Name of the variable. Null values will be replaced 
         * by empty strings.
	 */
	@Override
	public synchronized @Nonnull String getValueString()
	{
		return getVarName();
	}
	
	/**
	 * Override the new rebuild() feature to dynamically evaluate the configured
	 * environment variable name to get list of axis values to use for the
	 * current build.
	 * @see hudson.matrix.Axis#rebuild(hudson.matrix.MatrixBuild.MatrixBuildExecution)
	 * @return New list of axis values
	 */
	@Override
	public synchronized @Nonnull List<String> rebuild( @Nonnull MatrixBuild.MatrixBuildExecution context )
	{
		// clear any existing values to ensure we do not return old ones
		LOGGER.log( Level.FINE, "Rebuilding axis names from variable ''{0}''", varName);
		final List<String> newAxisValues = new ArrayList<String>(axisValues.size()); 
		try
		{
			// attempt to get the current environment variables
			final @Nonnull EnvVars vars = context.getBuild().getEnvironment( TaskListener.NULL );
			newAxisValues.addAll(expandVariableIfPresent(vars));
		}
		catch( Exception e )
		{
			LOGGER.log( Level.SEVERE, "Failed to build list of names: {0}", e);
		}

		// validate result list before returning it
		if (newAxisValues.isEmpty()) {
			LOGGER.fine( "Axis values list is empty. Adding 'default' value" );    
			newAxisValues.add( "default" );
		}
		LOGGER.log( Level.FINE, "Returning axis list {0}", newAxisValues);
		
		// Add values to the cache
		axisValues.clear();
		axisValues.addAll(newAxisValues);
		
		return newAxisValues;
	}

	private List<String> expandVariableIfPresent( Map<String, String> vars )
	{
		String varValue = vars.get( varName );
		if( varValue != null )
		{
			LOGGER.log( Level.FINE, "Variable value is ''{0}''", varValue);
			// only spaces are supported as separators, as per the original axis value definition
			return Arrays.asList(Util.tokenize(varValue));
		}
		return Collections.emptyList();
	}

	private static Map<String, String> getGlobalProperties()
	{
		Map<String, String> globalProperties = new HashMap<String, String>();
		for( NodeProperty<?> nodeProperty : Jenkins.getInstance().getGlobalNodeProperties() )
		{
			if( nodeProperty instanceof EnvironmentVariablesNodeProperty )
			{
				globalProperties.putAll(((EnvironmentVariablesNodeProperty) nodeProperty).getEnvVars());
			}
		}
		return globalProperties;
	}

	/**
	 * Descriptor for this plugin.
	 */
	@Extension
	public static class DescriptorImpl extends AxisDescriptor
	{
		/**
		 * Overridden to create a new instance of our Axis extension from UI
		 * values.
		 * @see hudson.model.Descriptor#newInstance(org.kohsuke.stapler.StaplerRequest,
		 *      net.sf.json.JSONObject)
		 */
		@Override
		public Axis newInstance( StaplerRequest req, JSONObject formData ) throws FormException
		{
			return new DynamicAxis( formData.getString( "name" ), formData.getString( "valueString" ) );
		}

		/**
		 * Overridden to provide our own display name.
		 * @see hudson.model.Descriptor#getDisplayName()
		 */
		@Override
		public String getDisplayName()
		{
			return "Dynamic Axis";
		}

		/**
		 * Ensures the value is a valid environment variable. Since some
		 * variables are not available until build time a warning is generated
		 * if the name is valid but cannot be found in the current environment.
		 * @param value
		 * @return
		 */
		public FormValidation doCheckValueString( @QueryParameter
		String value )
		{
			// must have a value
			if( value == null || value.length() == 0 )
			{
				return FormValidation.error( Messages.configNameRequired() );
			}

			// check for non-portable characters
			Pattern pattern = Pattern.compile( "[^\\p{Alnum}_]+" );
			if( pattern.matcher( value ).find() )
			{
				return FormValidation.warning( Messages.configPortableName() );
			}

			// see if it exists in the system or global variables; if not we cannot tell if it is valid or not
			String content = System.getenv( value );
			if( content == null )
			{
				content = getGlobalProperties().get( value );
			}
			if( content == null )
			{
				return FormValidation.warning( Messages.configBuildVariable() );
			}

			// should be ok - display current value so user can verify contents are okay to use as axis values
			return FormValidation.ok( Messages.configCurrentValue( content ) );
		}
	}
}
