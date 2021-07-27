import C from '../actions/constants';
import { SCAN_TYPES } from '../../utils/CommonUtils';

export default function (state={ response: { scanType: SCAN_TYPES.PAT_TYPE }}, action) {
    switch(action.type) {
        case C.SCAN_TYPE_DATA:
            return {...state, response: action.payload};
        default:
            return state;
    }
}
