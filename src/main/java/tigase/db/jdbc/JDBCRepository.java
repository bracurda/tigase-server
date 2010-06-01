/*
 * Tigase Jabber/XMPP Server
 * Copyright (C) 2004-2007 "Artur Hefczyc" <artur.hefczyc@tigase.org>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. Look for COPYING file in the top folder.
 * If not, see http://www.gnu.org/licenses/.
 *
 * $Rev$
 * Last modified by $Author$
 * $Date$
 */

package tigase.db.jdbc;

//~--- non-JDK imports --------------------------------------------------------

import tigase.db.AuthorizationException;
import tigase.db.DBInitException;
import tigase.db.TigaseDBException;
import tigase.db.UserAuthRepository;
import tigase.db.UserAuthRepositoryImpl;
import tigase.db.UserExistsException;
import tigase.db.UserNotFoundException;
import tigase.db.UserRepository;

import tigase.util.SimpleCache;

import tigase.xmpp.BareJID;

//~--- JDK imports ------------------------------------------------------------

import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.StringTokenizer;
import java.util.logging.Level;
import java.util.logging.Logger;

//~--- classes ----------------------------------------------------------------

/**
 * Not synchronized implementation!
 * Musn't be used by more than one thread at the same time.
 * <p>
 * Thanks to Daniele for better unique IDs handling.
 * Created: Thu Oct 26 11:48:53 2006
 * </p>
 * @author <a href="mailto:artur.hefczyc@tigase.org">Artur Hefczyc</a>
 * @author <a href="mailto:piras@tiscali.com">Daniele</a>
 * @version $Rev$
 */
public class JDBCRepository implements UserAuthRepository, UserRepository {
	private static final Logger log = Logger.getLogger(JDBCRepository.class.getName());

	/** Field description */
	public static final String DEF_USERS_TBL = "tig_users";

	/** Field description */
	public static final String DEF_NODES_TBL = "tig_nodes";

	/** Field description */
	public static final String DEF_PAIRS_TBL = "tig_pairs";

	/** Field description */
	public static final String DEF_MAXIDS_TBL = "tig_max_ids";

	/** Field description */
	public static final String DEF_ROOT_NODE = "root";
	private static final String USER_STR = "User: ";

	/** Field description */
	public static final String DERBY_CONNVALID_QUERY = "values 1";

	/** Field description */
	public static final String JDBC_CONNVALID_QUERY = "select 1";

	/** Field description */
	public static final String DERBY_GETSCHEMAVER_QUERY =
		"values TigGetDBProperty('schema-version')";

	/** Field description */
	public static final String JDBC_GETSCHEMAVER_QUERY =
		"select TigGetDBProperty('schema-version')";

	//~--- fields ---------------------------------------------------------------

	private CallableStatement all_users_sp = null;
	private UserAuthRepository auth = null;

	// Cache moved to connection pool
	private Map<String, Object> cache = null;
	private Connection conn = null;
	private PreparedStatement conn_valid_st = null;
	private long connectionValidateInterval = 1000 * 60;
	private PreparedStatement data_for_node_st = null;
	private String db_conn = null;
	private PreparedStatement insert_key_val_st = null;
	private PreparedStatement keys_for_node_st = null;
	private long lastConnectionValidated = 0;
	private CallableStatement node_add_sp = null;
	private PreparedStatement nodes_for_node_st = null;

	// private String users_tbl = DEF_USERS_TBL;
	private String nodes_tbl = DEF_NODES_TBL;
	private String pairs_tbl = DEF_PAIRS_TBL;
	private PreparedStatement remove_key_data_st = null;

	// private String maxids_tbl = DEF_MAXIDS_TBL;
	private String root_node = DEF_ROOT_NODE;
	private CallableStatement uid_sp = null;
	private CallableStatement user_add_sp = null;
	private CallableStatement user_del_sp = null;
	private CallableStatement users_count_sp = null;
	private PreparedStatement users_domain_count_st = null;
	private boolean derby_mode = false;
	private boolean autoCreateUser = false;

	//~--- methods --------------------------------------------------------------

	/**
	 * Describe <code>addDataList</code> method here.
	 *
	 * @param user_id a <code>String</code> value
	 * @param subnode a <code>String</code> value
	 * @param key a <code>String</code> value
	 * @param list a <code>String[]</code> value
	 * @exception UserNotFoundException if an error occurs
	 * @throws TigaseDBException
	 */
	@Override
	public void addDataList(BareJID user_id, final String subnode, final String key,
			final String[] list)
			throws UserNotFoundException, TigaseDBException {
		long uid = -2;
		long nid = -2;

		try {
			checkConnection();
			uid = getUserUID(user_id, autoCreateUser);
			nid = getNodeNID(uid, subnode);

			if (nid < 0) {
				try {
					nid = createNodePath(user_id, subnode);
				} catch (SQLException e) {

					// This may happen in cluster node, when 2 nodes at the same
					// time write data to the same location, like offline messages....
					// Let's try to get the nid again.
					nid = getNodeNID(uid, subnode);
				}
			}

			synchronized (insert_key_val_st) {
				insert_key_val_st.setLong(1, nid);
				insert_key_val_st.setLong(2, uid);
				insert_key_val_st.setString(3, key);

				for (String val : list) {
					insert_key_val_st.setString(4, val);
					insert_key_val_st.executeUpdate();
				}    // end of for (String val: list)
			}
		} catch (SQLException e) {
			throw new TigaseDBException("Error adding data list, user_id: " + user_id
					+ ", subnode: " + subnode + ", key: " + key + ", uid: " + uid + ", nid: " + nid
						+ ", list: " + Arrays.toString(list), e);
		}

		// cache.put(user_id+"/"+subnode+"/"+key, list);
	}

	/**
	 * Describe <code>addUser</code> method here.
	 *
	 * @param user_id a <code>String</code> value
	 * @exception UserExistsException if an error occurs
	 * @throws TigaseDBException
	 */
	@Override
	public void addUser(BareJID user_id) throws UserExistsException, TigaseDBException {
		try {
			addUserRepo(user_id);
		} catch (SQLException e) {
			throw new UserExistsException("Error adding user to repository: ", e);
		}
	}

	/**
	 * Describe <code>addUser</code> method here.
	 *
	 * @param user a <code>String</code> value
	 * @param password a <code>String</code> value
	 * @exception UserExistsException if an error occurs
	 * @exception TigaseDBException if an error occurs
	 */
	@Override
	public void addUser(BareJID user, final String password)
			throws UserExistsException, TigaseDBException {
		auth.addUser(user, password);
	}

	/**
	 * Describe <code>digestAuth</code> method here.
	 *
	 * @param user a <code>String</code> value
	 * @param digest a <code>String</code> value
	 * @param id a <code>String</code> value
	 * @param alg a <code>String</code> value
	 * @return a <code>boolean</code> value
	 *
	 * @throws AuthorizationException
	 * @exception UserNotFoundException if an error occurs
	 * @exception TigaseDBException if an error occurs
	 */
	@Override
	public boolean digestAuth(BareJID user, final String digest, final String id,
			final String alg)
			throws UserNotFoundException, TigaseDBException, AuthorizationException {
		return auth.digestAuth(user, digest, id, alg);
	}

	//~--- get methods ----------------------------------------------------------

	/**
	 * Describe <code>getData</code> method here.
	 *
	 * @param user_id a <code>String</code> value
	 * @param subnode a <code>String</code> value
	 * @param key a <code>String</code> value
	 * @param def a <code>String</code> value
	 * @return a <code>String</code> value
	 * @exception UserNotFoundException if an error occurs
	 * @throws TigaseDBException
	 */
	@Override
	public String getData(BareJID user_id, final String subnode, final String key,
			final String def)
			throws UserNotFoundException, TigaseDBException {

		// String[] cache_res = (String[])cache.get(user_id+"/"+subnode+"/"+key);
		// if (cache_res != null) {
		// return cache_res[0];
		// } // end of if (result != null)
		ResultSet rs = null;

		try {
			checkConnection();

			long nid = getNodeNID(user_id, subnode);

			if (log.isLoggable(Level.FINEST)) {
				log.finest("Loading data for key: " + key + ", user: " + user_id + ", node: "
						+ subnode + ", def: " + def + ", found nid: " + nid);
			}

			synchronized (data_for_node_st) {
				if (nid > 0) {
					String result = def;

					data_for_node_st.setLong(1, nid);
					data_for_node_st.setString(2, key);
					rs = data_for_node_st.executeQuery();

					if (rs.next()) {
						result = rs.getString(1);

						if (log.isLoggable(Level.FINEST)) {
							log.finest("Found data: " + result);
						}
					}

					// cache.put(user_id+"/"+subnode+"/"+key, new String[] {result});
					return result;
				} else {
					return def;
				}    // end of if (nid > 0) else
			}
		} catch (SQLException e) {
			throw new TigaseDBException("Error getting data list.", e);
		} finally {
			release(null, rs);
		}
	}

	/**
	 * Describe <code>getData</code> method here.
	 *
	 * @param user_id a <code>String</code> value
	 * @param subnode a <code>String</code> value
	 * @param key a <code>String</code> value
	 * @return a <code>String</code> value
	 * @exception UserNotFoundException if an error occurs
	 * @throws TigaseDBException
	 */
	@Override
	public String getData(BareJID user_id, final String subnode, final String key)
			throws UserNotFoundException, TigaseDBException {
		return getData(user_id, subnode, key, null);
	}

	/**
	 * Describe <code>getData</code> method here.
	 *
	 * @param user_id a <code>String</code> value
	 * @param key a <code>String</code> value
	 * @return a <code>String</code> value
	 * @exception UserNotFoundException if an error occurs
	 * @throws TigaseDBException
	 */
	@Override
	public String getData(BareJID user_id, final String key)
			throws UserNotFoundException, TigaseDBException {
		return getData(user_id, null, key, null);
	}

	/**
	 * Describe <code>getDataList</code> method here.
	 *
	 * @param user_id a <code>String</code> value
	 * @param subnode a <code>String</code> value
	 * @param key a <code>String</code> value
	 * @return a <code>String[]</code> value
	 * @exception UserNotFoundException if an error occurs
	 * @throws TigaseDBException
	 */
	@Override
	public String[] getDataList(BareJID user_id, final String subnode, final String key)
			throws UserNotFoundException, TigaseDBException {

		// String[] cache_res = (String[])cache.get(user_id+"/"+subnode+"/"+key);
		// if (cache_res != null) {
		// return cache_res;
		// } // end of if (result != null)
		ResultSet rs = null;

		try {
			checkConnection();

			long nid = getNodeNID(user_id, subnode);

			synchronized (data_for_node_st) {
				if (nid > 0) {
					List<String> results = new ArrayList<String>();

					data_for_node_st.setLong(1, nid);
					data_for_node_st.setString(2, key);
					rs = data_for_node_st.executeQuery();

					while (rs.next()) {
						results.add(rs.getString(1));
					}

					String[] result = (results.size() == 0)
						? null : results.toArray(new String[results.size()]);

					// cache.put(user_id+"/"+subnode+"/"+key, result);
					return result;
				} else {
					return null;
				}    // end of if (nid > 0) else
			}
		} catch (SQLException e) {
			throw new TigaseDBException("Error getting data list.", e);
		} finally {
			release(null, rs);
		}
	}

	/**
	 * Describe <code>getKeys</code> method here.
	 *
	 * @param user_id a <code>String</code> value
	 * @param subnode a <code>String</code> value
	 * @return a <code>String[]</code> value
	 * @exception UserNotFoundException if an error occurs
	 * @throws TigaseDBException
	 */
	@Override
	public String[] getKeys(BareJID user_id, final String subnode)
			throws UserNotFoundException, TigaseDBException {
		ResultSet rs = null;

		try {
			checkConnection();

			long nid = getNodeNID(user_id, subnode);

			if (nid > 0) {
				List<String> results = new ArrayList<String>();

				synchronized (keys_for_node_st) {
					keys_for_node_st.setLong(1, nid);
					rs = keys_for_node_st.executeQuery();

					while (rs.next()) {
						results.add(rs.getString(1));
					}

					return (results.size() == 0) ? null : results.toArray(new String[results.size()]);
				}
			} else {
				return null;
			}    // end of if (nid > 0) else
		} catch (SQLException e) {
			throw new TigaseDBException("Error getting subnodes list.", e);
		} finally {
			release(null, rs);
		}
	}

	/**
	 * Describe <code>getKeys</code> method here.
	 *
	 * @param user_id a <code>String</code> value
	 * @return a <code>String[]</code> value
	 * @exception UserNotFoundException if an error occurs
	 * @throws TigaseDBException
	 */
	@Override
	public String[] getKeys(BareJID user_id) throws UserNotFoundException, TigaseDBException {
		return getKeys(user_id, null);
	}

	/**
	 * Method description
	 *
	 *
	 * @return
	 */
	@Override
	public String getResourceUri() {
		return db_conn;
	}

	/**
	 * Describe <code>getSubnodes</code> method here.
	 *
	 * @param user_id a <code>String</code> value
	 * @param subnode a <code>String</code> value
	 * @return a <code>String[]</code> value
	 * @exception UserNotFoundException if an error occurs
	 * @throws TigaseDBException
	 */
	@Override
	public String[] getSubnodes(BareJID user_id, final String subnode)
			throws UserNotFoundException, TigaseDBException {
		ResultSet rs = null;

		try {
			checkConnection();

			long nid = getNodeNID(user_id, subnode);

			synchronized (nodes_for_node_st) {
				if (nid > 0) {
					List<String> results = new ArrayList<String>();

					nodes_for_node_st.setLong(1, nid);
					rs = nodes_for_node_st.executeQuery();

					while (rs.next()) {
						results.add(rs.getString(2));
					}

					return (results.size() == 0) ? null : results.toArray(new String[results.size()]);
				} else {
					return null;
				}    // end of if (nid > 0) else
			}
		} catch (SQLException e) {
			throw new TigaseDBException("Error getting subnodes list.", e);
		} finally {
			release(null, rs);
		}
	}

	/**
	 * Describe <code>getSubnodes</code> method here.
	 *
	 * @param user_id a <code>String</code> value
	 * @return a <code>String[]</code> value
	 * @exception UserNotFoundException if an error occurs
	 * @throws TigaseDBException
	 */
	@Override
	public String[] getSubnodes(BareJID user_id)
			throws UserNotFoundException, TigaseDBException {
		return getSubnodes(user_id, null);
	}

	/**
	 * Method description
	 *
	 *
	 * @param user_id
	 *
	 * @return
	 *
	 * @throws TigaseDBException
	 */
	@Override
	public long getUserUID(BareJID user_id) throws TigaseDBException {
		Long cache_res = (Long) cache.get(user_id.toString());

		if (cache_res != null) {
			return cache_res.longValue();
		}    // end of if (result != null)

		ResultSet rs = null;
		long result = -1;

		try {
			synchronized (uid_sp) {
				uid_sp.setString(1, user_id.toString());
				rs = uid_sp.executeQuery();

				if (rs.next()) {
					result = rs.getLong(1);
				} else {
					result = -1;
				}
			}
		} catch (SQLException e) {
			throw new TigaseDBException("Error retrieving user UID from repository: ", e);
		} finally {
			release(null, rs);
		}

		cache.put(user_id.toString(), Long.valueOf(result));

		return result;
	}

	/**
	 * <code>getUsers</code> method is thread safe. It uses local variable
	 * for storing <code>Statement</code>.
	 *
	 * @return a <code>List</code> of user IDs from database.
	 *
	 * @throws TigaseDBException
	 */
	@Override
	public List<BareJID> getUsers() throws TigaseDBException {
		ResultSet rs = null;

		try {
			checkConnection();

			// Load all user ids from database
			rs = all_users_sp.executeQuery();

			List<BareJID> users = new ArrayList<BareJID>();

			while (rs.next()) {
				users.add(BareJID.bareJIDInstanceNS(rs.getString(1)));
			}    // end of while (rs.next())

			return users;
		} catch (SQLException e) {
			throw new TigaseDBException("Problem loading user list from repository", e);
		} finally {
			release(null, rs);
			rs = null;
		}
	}

	/**
	 * <code>getUsersCount</code> method is thread safe. It uses local variable
	 * for storing <code>Statement</code>.
	 *
	 * @return a <code>long</code> number of user accounts in database.
	 */
	@Override
	public long getUsersCount() {
		ResultSet rs = null;

		try {
			checkConnection();

			long users = -1;

			synchronized (users_count_sp) {

				// Load all user count from database
				rs = users_count_sp.executeQuery();

				if (rs.next()) {
					users = rs.getLong(1);
				}    // end of while (rs.next())
			}

			return users;
		} catch (SQLException e) {
			return -1;

			// throw new TigaseDBException("Problem loading user list from repository", e);
		} finally {
			release(null, rs);
			rs = null;
		}
	}

	/**
	 * Method description
	 *
	 *
	 * @param domain
	 *
	 * @return
	 */
	@Override
	public long getUsersCount(String domain) {
		ResultSet rs = null;

		try {
			checkConnection();

			long users = -1;

			synchronized (users_domain_count_st) {

				// Load all user count from database
				users_domain_count_st.setString(1, "%@" + domain);
				rs = users_domain_count_st.executeQuery();

				if (rs.next()) {
					users = rs.getLong(1);
				}    // end of while (rs.next())
			}

			return users;
		} catch (SQLException e) {
			return -1;

			// throw new TigaseDBException("Problem loading user list from repository", e);
		} finally {
			release(null, rs);
			rs = null;
		}
	}

	//~--- methods --------------------------------------------------------------

	/**
	 * Describe <code>initRepository</code> method here.
	 *
	 * @param connection_str a <code>String</code> value
	 * @param params
	 *
	 * @throws DBInitException
	 */
	@Override
	public void initRepository(final String connection_str, Map<String, String> params)
			throws DBInitException {
		db_conn = connection_str;

		if (db_conn.contains("autoCreateUser=true")) {
			autoCreateUser = true;
		}    // end of if (db_conn.contains())

		try {
			initRepo();
			log.info("Initialized database connection: " + connection_str);
		} catch (SQLException e) {
			conn = null;

			throw new DBInitException("Problem initializing jdbc connection: " + db_conn, e);
		}
	}

	/**
	 * Method description
	 *
	 *
	 * @param user
	 *
	 * @throws TigaseDBException
	 * @throws UserNotFoundException
	 */
	@Override
	public void logout(BareJID user) throws UserNotFoundException, TigaseDBException {
		auth.logout(user);
	}

	/**
	 * Describe <code>otherAuth</code> method here.
	 *
	 * @param props a <code>Map</code> value
	 * @return a <code>boolean</code> value
	 * @exception UserNotFoundException if an error occurs
	 * @exception TigaseDBException if an error occurs
	 * @exception AuthorizationException if an error occurs
	 */
	@Override
	public boolean otherAuth(final Map<String, Object> props)
			throws UserNotFoundException, TigaseDBException, AuthorizationException {
		return auth.otherAuth(props);
	}

	// Implementation of tigase.db.UserAuthRepository

	/**
	 * Describe <code>plainAuth</code> method here.
	 *
	 * @param user a <code>String</code> value
	 * @param password a <code>String</code> value
	 * @return a <code>boolean</code> value
	 *
	 * @throws AuthorizationException
	 * @exception UserNotFoundException if an error occurs
	 * @exception TigaseDBException if an error occurs
	 */
	@Override
	public boolean plainAuth(BareJID user, final String password)
			throws UserNotFoundException, TigaseDBException, AuthorizationException {
		return auth.plainAuth(user, password);
	}

	/**
	 * Method description
	 *
	 *
	 * @param authProps
	 */
	@Override
	public void queryAuth(Map<String, Object> authProps) {
		auth.queryAuth(authProps);
	}

	/**
	 * Describe <code>removeData</code> method here.
	 *
	 * @param user_id a <code>String</code> value
	 * @param subnode a <code>String</code> value
	 * @param key a <code>String</code> value
	 * @exception UserNotFoundException if an error occurs
	 * @throws TigaseDBException
	 */
	@Override
	public void removeData(BareJID user_id, final String subnode, final String key)
			throws UserNotFoundException, TigaseDBException {

		// cache.remove(user_id+"/"+subnode+"/"+key);
		try {
			checkConnection();

			long nid = getNodeNID(user_id, subnode);

			synchronized (remove_key_data_st) {
				if (nid > 0) {
					remove_key_data_st.setLong(1, nid);
					remove_key_data_st.setString(2, key);
					remove_key_data_st.executeUpdate();
				}
			}
		} catch (SQLException e) {
			throw new TigaseDBException("Error getting subnodes list.", e);
		}
	}

	/**
	 * Describe <code>removeData</code> method here.
	 *
	 * @param user_id a <code>String</code> value
	 * @param key a <code>String</code> value
	 * @exception UserNotFoundException if an error occurs
	 * @throws TigaseDBException
	 */
	@Override
	public void removeData(BareJID user_id, final String key)
			throws UserNotFoundException, TigaseDBException {
		removeData(user_id, null, key);
	}

	/**
	 * Describe <code>removeSubnode</code> method here.
	 *
	 * @param user_id a <code>String</code> value
	 * @param subnode a <code>String</code> value
	 * @exception UserNotFoundException if an error occurs
	 * @throws TigaseDBException
	 */
	@Override
	public void removeSubnode(BareJID user_id, final String subnode)
			throws UserNotFoundException, TigaseDBException {
		if (subnode == null) {
			return;
		}    // end of if (subnode == null)

		try {
			checkConnection();

			long nid = getNodeNID(user_id, subnode);

			if (nid > 0) {
				deleteSubnode(nid);
				cache.remove(user_id + "/" + subnode);
			}
		} catch (SQLException e) {
			throw new TigaseDBException("Error getting subnodes list.", e);
		}
	}

	/**
	 * <code>removeUser</code> method is thread safe. It uses local variable
	 * for storing <code>Statement</code>.
	 *
	 * @param user_id a <code>String</code> value the user Jabber ID.
	 *
	 * @throws TigaseDBException
	 * @exception UserNotFoundException if an error occurs
	 */
	@Override
	public void removeUser(BareJID user_id) throws UserNotFoundException, TigaseDBException {
		Statement stmt = null;
		ResultSet rs = null;
		String query = null;

		try {
			checkConnection();
			stmt = conn.createStatement();

			// Get user account uid
			long uid = getUserUID(user_id, autoCreateUser);

			// Remove all user enrties from pairs table
			query = "delete from " + pairs_tbl + " where uid = " + uid;
			stmt.executeUpdate(query);

			// Remove all user entries from nodes table
			query = "delete from " + nodes_tbl + " where uid = " + uid;
			stmt.executeUpdate(query);

			// Remove user account from users table
			synchronized (user_del_sp) {
				user_del_sp.setString(1, user_id.toString());
				user_del_sp.executeUpdate();
			}
		} catch (SQLException e) {
			throw new TigaseDBException("Error removing user from repository: " + query, e);
		} finally {
			release(stmt, rs);
			stmt = null;
			cache.remove(user_id.toString());

			// cache.clear();
		}
	}

	//~--- set methods ----------------------------------------------------------

	/**
	 * Describe <code>setData</code> method here.
	 *
	 * @param user_id a <code>String</code> value
	 * @param subnode a <code>String</code> value
	 * @param key a <code>String</code> value
	 * @param value a <code>String</code> value
	 * @exception UserNotFoundException if an error occurs
	 * @throws TigaseDBException
	 */
	@Override
	public void setData(BareJID user_id, final String subnode, final String key,
			final String value)
			throws UserNotFoundException, TigaseDBException {
		setDataList(user_id, subnode, key, new String[] { value });
	}

	/**
	 * Describe <code>setData</code> method here.
	 *
	 * @param user_id a <code>String</code> value
	 * @param key a <code>String</code> value
	 * @param value a <code>String</code> value
	 * @exception UserNotFoundException if an error occurs
	 * @throws TigaseDBException
	 */
	@Override
	public void setData(BareJID user_id, final String key, final String value)
			throws UserNotFoundException, TigaseDBException {
		setData(user_id, null, key, value);
	}

	/**
	 * Describe <code>setDataList</code> method here.
	 *
	 * @param user_id a <code>String</code> value
	 * @param subnode a <code>String</code> value
	 * @param key a <code>String</code> value
	 * @param list a <code>String[]</code> value
	 * @exception UserNotFoundException if an error occurs
	 * @throws TigaseDBException
	 */
	@Override
	public void setDataList(BareJID user_id, final String subnode, final String key,
			final String[] list)
			throws UserNotFoundException, TigaseDBException {
		removeData(user_id, subnode, key);
		addDataList(user_id, subnode, key, list);
	}

	//~--- methods --------------------------------------------------------------

	/**
	 * Method description
	 *
	 *
	 * @param user
	 * @param password
	 *
	 * @throws TigaseDBException
	 */
	@Override
	public void updatePassword(BareJID user, final String password) throws TigaseDBException {
		auth.updatePassword(user, password);
	}

	/**
	 * Method description
	 *
	 *
	 * @param user
	 *
	 * @return
	 */
	@Override
	public boolean userExists(BareJID user) {
		try {
			getUserUID(user, false);

			return true;
		} catch (Exception e) {
			return false;
		}
	}

	private long addNode(long uid, long parent_nid, String node_name)
			throws SQLException, TigaseDBException {
		ResultSet rs = null;
		long new_nid = -1;

		synchronized (node_add_sp) {
			try {
				if (parent_nid < 0) {
					node_add_sp.setNull(1, Types.BIGINT);
				} else {
					node_add_sp.setLong(1, parent_nid);
				}    // end of else

				node_add_sp.setLong(2, uid);
				node_add_sp.setString(3, node_name);
				rs = node_add_sp.executeQuery();

				if (rs.next()) {
					return rs.getLong(1);
				} else {
					log.warning("Missing NID after adding new node...");

					throw new TigaseDBException("Propeblem adding new node. "
							+ "The SP should return nid or fail");
				}    // end of if (isnext) else
			} finally {
				release(null, rs);
			}
		}

		// return new_nid;
	}

	/**
	 * <code>addUserRepo</code> method is thread safe. It uses local variable
	 * for storing <code>Statement</code>.
	 *
	 * @param user_id a <code>String</code> value of the user ID.
	 * @return a <code>long</code> value of <code>uid</code> database user ID.
	 * @exception SQLException if an error occurs
	 */
	private long addUserRepo(BareJID user_id) throws SQLException, TigaseDBException {
		checkConnection();

		ResultSet rs = null;
		long uid = -1;

		synchronized (user_add_sp) {
			try {
				user_add_sp.setString(1, user_id.toString());
				user_add_sp.setNull(2, Types.VARCHAR);
				rs = user_add_sp.executeQuery();

				if (rs.next()) {
					uid = rs.getLong(1);

					// addNode(uid, -1, root_node);
				} else {
					log.warning("Missing UID after adding new user...");

					throw new TigaseDBException("Propeblem adding new user to repository. "
							+ "The SP should return uid or fail");
				}    // end of if (isnext) else
			} finally {
				release(null, rs);
			}
		}

		cache.put(user_id.toString(), Long.valueOf(uid));

		return uid;
	}

	private String buildNodeQuery(long uid, String node_path) {
		String query = "select nid as nid1 from " + nodes_tbl + " where (uid = " + uid + ")"
			+ " AND (parent_nid is null)" + " AND (node = '" + root_node + "')";

		if (node_path == null) {
			return query;
		} else {
			StringTokenizer strtok = new StringTokenizer(node_path, "/", false);
			int cnt = 1;
			String subquery = query;

			while (strtok.hasMoreTokens()) {
				String token = strtok.nextToken();

				++cnt;
				subquery = "select nid as nid" + cnt + ", node as node" + cnt + " from " + nodes_tbl
						+ ", (" + subquery + ") nodes" + (cnt - 1) + " where (parent_nid = nid"
							+ (cnt - 1) + ")" + " AND (node = '" + token + "')";
			}    // end of while (strtok.hasMoreTokens())

			return subquery;
		}      // end of else
	}

	private boolean checkConnection() throws SQLException {
		ResultSet rs = null;

		try {
			synchronized (conn_valid_st) {
				long tmp = System.currentTimeMillis();

				if ((tmp - lastConnectionValidated) >= connectionValidateInterval) {
					rs = conn_valid_st.executeQuery();
					lastConnectionValidated = tmp;
				}    // end of if ()
			}
		} catch (Exception e) {
			initRepo();
		} finally {
			release(null, rs);
		}        // end of try-catch

		return true;
	}

	// Implementation of tigase.db.UserRepository
	private void checkDBSchema() throws SQLException {
		String schema_version = "1.0";
		String query = (derby_mode ? DERBY_GETSCHEMAVER_QUERY : JDBC_GETSCHEMAVER_QUERY);
		Statement stmt = conn.createStatement();
		ResultSet rs = stmt.executeQuery(query);

		try {
			if (rs.next()) {
				schema_version = rs.getString(1);

				if (false == "4.0".equals(schema_version)) {
					System.err.println("\n\nPlease upgrade database schema now.");
					System.err.println("Current scheme version is: " + schema_version
							+ ", expected: 4.0");
					System.err.println("Check the schema upgrade guide at the address:");
					System.err.println("http://www.tigase.org/en/mysql-db-schema-upgrade-4-0");
					System.err.println("----");
					System.err.println("If you have upgraded your schema and you are still");
					System.err.println("experiencing this problem please contact support at");
					System.err.println("e-mail address: support@tigase.org");

					// e.printStackTrace();
					System.exit(100);
				}
			}
		} finally {
			release(stmt, rs);
		}    // end of try-catch
	}

	private long createNodePath(BareJID user_id, String node_path)
			throws SQLException, UserNotFoundException, TigaseDBException {
		if (node_path == null) {

			// Or should I throw NullPointerException?
			return getNodeNID(user_id, null);
		}    // end of if (node_path == null)

		long uid = getUserUID(user_id, autoCreateUser);
		long nid = getNodeNID(uid, null);
		StringTokenizer strtok = new StringTokenizer(node_path, "/", false);
		StringBuilder built_path = new StringBuilder();

		while (strtok.hasMoreTokens()) {
			String token = strtok.nextToken();

			built_path.append("/").append(token);

			long cur_nid = getNodeNID(uid, built_path.toString());

			if (cur_nid > 0) {
				nid = cur_nid;
			} else {
				nid = addNode(uid, nid, token);
			}    // end of if (cur_nid > 0) else
		}      // end of while (strtok.hasMoreTokens())

		return nid;
	}

	private void deleteSubnode(long nid) throws SQLException {
		Statement stmt = null;
		ResultSet rs = null;
		String query = null;

		try {
			stmt = conn.createStatement();
			query = "delete from " + pairs_tbl + " where nid = " + nid;
			stmt.executeUpdate(query);
			query = "delete from " + nodes_tbl + " where nid = " + nid;
			stmt.executeUpdate(query);
		} finally {
			release(stmt, rs);
		}
	}

	//~--- get methods ----------------------------------------------------------

	private long getNodeNID(long uid, String node_path)
			throws SQLException, TigaseDBException, UserNotFoundException {
		String query = buildNodeQuery(uid, node_path);

		if (log.isLoggable(Level.FINEST)) {
			log.finest(query);
		}

		Statement stmt = null;
		ResultSet rs = null;
		long nid = -1;

		try {
			stmt = conn.createStatement();
			rs = stmt.executeQuery(query);

			if (rs.next()) {
				nid = rs.getLong(1);
			} else {
				nid = -1;
			}    // end of if (isnext) else

			if (nid <= 0) {
				if (node_path == null) {
					log.info("Missing root node, database upgrade or bug in the code? Adding missing "
							+ "root node now.");
					nid = addNode(uid, -1, "root");
				} else {
					if (log.isLoggable(Level.FINEST)) {
						log.finest("Missing nid for node path: " + node_path + " and uid: " + uid);
					}
				}
			}

			return nid;
		} finally {
			release(stmt, rs);
			stmt = null;
			rs = null;
		}
	}

	private long getNodeNID(BareJID user_id, String node_path)
			throws SQLException, UserNotFoundException, TigaseDBException {
		Long cache_res = (Long) cache.get(user_id + "/" + node_path);

		if (cache_res != null) {
			return cache_res.longValue();
		}    // end of if (result != null)

		long uid = getUserUID(user_id, autoCreateUser);
		long result = getNodeNID(uid, node_path);

		if (result > 0) {
			cache.put(user_id + "/" + node_path, Long.valueOf(result));
		}    // end of if (result > 0)

		return result;
	}

	private long getUserUID(BareJID user_id, boolean autoCreate)
			throws SQLException, UserNotFoundException, TigaseDBException {
		long result = getUserUID(user_id);

		if (result <= 0) {
			if (autoCreate) {
				result = addUserRepo(user_id);
			} else {
				throw new UserNotFoundException("User does not exist: " + user_id);
			}    // end of if (autoCreate) else
		}      // end of if (isnext) else

		return result;
	}

	//~--- methods --------------------------------------------------------------

	private void initPreparedStatements() throws SQLException {
		String query = "{ call TigGetUserDBUid(?) }";

		uid_sp = conn.prepareCall(query);
		query = "{ call TigAllUsersCount() }";
		users_count_sp = conn.prepareCall(query);
		query = "{ call TigAllUsers() }";
		all_users_sp = conn.prepareCall(query);
		query = "{ call TigAddUserPlainPw(?, ?) }";
		user_add_sp = conn.prepareCall(query);
		query = "{ call TigRemoveUser(?) }";
		user_del_sp = conn.prepareCall(query);
		query = "{ call TigAddNode(?, ?, ?) }";
		node_add_sp = conn.prepareCall(query);
		query = "select count(*) from tig_users where user_id like ?";
		users_domain_count_st = conn.prepareCall(query);
		query = "select pval from " + pairs_tbl + " where (nid = ?) AND (pkey = ?)";
		data_for_node_st = conn.prepareStatement(query);
		query = "select pkey from " + pairs_tbl + " where (nid = ?)";
		keys_for_node_st = conn.prepareStatement(query);
		query = "select nid, node from " + nodes_tbl + " where parent_nid = ?";
		nodes_for_node_st = conn.prepareStatement(query);
		query = "insert into " + pairs_tbl + " (nid, uid, pkey, pval) " + " values (?, ?, ?, ?)";
		insert_key_val_st = conn.prepareStatement(query);
		query = "delete from " + pairs_tbl + " where (nid = ?) AND (pkey = ?)";
		remove_key_data_st = conn.prepareStatement(query);
		query = (derby_mode ? DERBY_CONNVALID_QUERY : JDBC_CONNVALID_QUERY);
		conn_valid_st = conn.prepareStatement(query);
	}

	private void initRepo() throws SQLException {
		Statement stmt = null;
		ResultSet rs = null;

		try {
			synchronized (db_conn) {
				conn = DriverManager.getConnection(db_conn);
				conn.setAutoCommit(true);
				derby_mode = db_conn.startsWith("jdbc:derby");
				checkDBSchema();
				initPreparedStatements();
				auth = new UserAuthRepositoryImpl(this);
				stmt = conn.createStatement();

				if (db_conn.contains("cacheRepo=off")) {
					log.fine("Disabling cache.");
					cache = Collections.synchronizedMap(new RepoCache(0, -1000));
				} else {
					cache = Collections.synchronizedMap(new RepoCache(10000, 60 * 1000));
				}
			}
		} finally {
			release(stmt, rs);
			stmt = null;
			rs = null;
		}
	}

	private void release(Statement stmt, ResultSet rs) {
		if (rs != null) {
			try {
				rs.close();
			} catch (SQLException sqlEx) {}
		}

		if (stmt != null) {
			try {
				stmt.close();
			} catch (SQLException sqlEx) {}
		}
	}

	//~--- inner classes --------------------------------------------------------

	private class RepoCache extends SimpleCache<String, Object> {

		/**
		 * Constructs ...
		 *
		 *
		 * @param maxsize
		 * @param cache_time
		 */
		public RepoCache(int maxsize, long cache_time) {
			super(maxsize, cache_time);
		}

		//~--- methods ------------------------------------------------------------

		/**
		 * Method description
		 *
		 *
		 * @param key
		 *
		 * @return
		 */
		@Override
		public Object remove(Object key) {
			if (cache_off) {
				return null;
			}

			Object val = super.remove(key);
			String strk = key.toString();
			Iterator<String> ks = keySet().iterator();

			while (ks.hasNext()) {
				String k = ks.next().toString();

				if (k.startsWith(strk)) {
					ks.remove();
				}    // end of if (k.startsWith(strk))
			}      // end of while (ks.hasNext())

			return val;
		}
	}
}    // JDBCRepository


//~ Formatted in Sun Code Convention


//~ Formatted by Jindent --- http://www.jindent.com
